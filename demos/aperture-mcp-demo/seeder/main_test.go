package main

import (
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"os"
	"strings"
	"testing"
)

func TestProjectRequestBuildsJSONAPIPayload(t *testing.T) {
	project := Project{
		Name:        "Launch Readiness",
		Description: "Track the work needed to make the MCP demo useful.",
	}

	payload := newProjectRequest(project)

	data, ok := payload["data"].(map[string]interface{})
	if !ok {
		t.Fatalf("data was %T, want map[string]interface{}", payload["data"])
	}
	if got := data["type"]; got != "projects" {
		t.Fatalf("data.type = %v, want projects", got)
	}
	attrs, ok := data["attributes"].(map[string]interface{})
	if !ok {
		t.Fatalf("attributes was %T, want map[string]interface{}", data["attributes"])
	}
	if got := attrs["name"]; got != project.Name {
		t.Fatalf("attributes.name = %v, want %s", got, project.Name)
	}
	if got := attrs["description"]; got != project.Description {
		t.Fatalf("attributes.description = %v, want %s", got, project.Description)
	}
}

func TestTaskRequestBuildsProjectRelationship(t *testing.T) {
	task := Task{
		Title:     "Exercise tools/list",
		Notes:     "Confirm generated tools are visible.",
		Status:    "TODO",
		ProjectID: "project-123",
	}

	payload := newTaskRequest(task)

	data := payload["data"].(map[string]interface{})
	if got := data["type"]; got != "tasks" {
		t.Fatalf("data.type = %v, want tasks", got)
	}
	attrs := data["attributes"].(map[string]interface{})
	if got := attrs["title"]; got != task.Title {
		t.Fatalf("attributes.title = %v, want %s", got, task.Title)
	}
	if got := attrs["notes"]; got != task.Notes {
		t.Fatalf("attributes.notes = %v, want %s", got, task.Notes)
	}
	if got := attrs["status"]; got != task.Status {
		t.Fatalf("attributes.status = %v, want %s", got, task.Status)
	}
	relationships := data["relationships"].(map[string]interface{})
	project := relationships["project"].(map[string]interface{})
	relationshipData := project["data"].(map[string]interface{})
	if got := relationshipData["type"]; got != "projects" {
		t.Fatalf("project relationship type = %v, want projects", got)
	}
	if got := relationshipData["id"]; got != task.ProjectID {
		t.Fatalf("project relationship id = %v, want %s", got, task.ProjectID)
	}
}

func TestExtractResourceIDReadsJSONAPIDataID(t *testing.T) {
	var response map[string]interface{}
	if err := json.Unmarshal([]byte(`{"data":{"type":"projects","id":"42"}}`), &response); err != nil {
		t.Fatal(err)
	}

	id, err := extractResourceID(response)
	if err != nil {
		t.Fatalf("extractResourceID returned error: %v", err)
	}
	if id != "42" {
		t.Fatalf("id = %q, want 42", id)
	}
}

func TestFindProjectReturnsExistingSeedProject(t *testing.T) {
	s := testSeeder(func(r *http.Request) *http.Response {
		if r.Method != http.MethodGet || r.URL.Path != "/api/v1/projects" {
			t.Fatalf("unexpected request %s %s", r.Method, r.URL.Path)
		}
		return jsonResponse(`{
			"data": [{
				"type": "projects",
				"id": "project-42",
				"attributes": {
					"name": "MCP Demo Launch",
					"description": "A small project seeded so MCP clients can list, inspect, and update real Aperture data."
				}
			}]
		}`)
	})

	id, err := s.findProject("token", Project{
		Name:        "MCP Demo Launch",
		Description: "A small project seeded so MCP clients can list, inspect, and update real Aperture data.",
	})
	if err != nil {
		t.Fatalf("findProject returned error: %v", err)
	}
	if id != "project-42" {
		t.Fatalf("id = %q, want project-42", id)
	}
}

func TestFindTaskMatchesTitleNotesAndProject(t *testing.T) {
	s := testSeeder(func(r *http.Request) *http.Response {
		if r.Method != http.MethodGet || r.URL.Path != "/api/v1/tasks" {
			t.Fatalf("unexpected request %s %s", r.Method, r.URL.Path)
		}
		return jsonResponse(`{
			"data": [{
				"type": "tasks",
				"id": "task-42",
				"attributes": {
					"title": "Verify generated tools",
					"notes": "Call tools/list and confirm Project and Task operations are available.",
					"status": "DONE"
				},
				"relationships": {
					"project": {
						"data": {
							"type": "projects",
							"id": "project-42"
						}
					}
				}
			}]
		}`)
	})

	exists, err := s.findTask("token", Task{
		Title:     "Verify generated tools",
		Notes:     "Call tools/list and confirm Project and Task operations are available.",
		Status:    "DONE",
		ProjectID: "project-42",
	})
	if err != nil {
		t.Fatalf("findTask returned error: %v", err)
	}
	if !exists {
		t.Fatal("findTask = false, want true")
	}
}

type roundTripFunc func(*http.Request) *http.Response

func (f roundTripFunc) RoundTrip(r *http.Request) (*http.Response, error) {
	return f(r), nil
}

func jsonResponse(body string) *http.Response {
	return &http.Response{
		StatusCode: http.StatusOK,
		Header:     http.Header{"Content-Type": []string{"application/vnd.api+json"}},
		Body:       io.NopCloser(strings.NewReader(body)),
	}
}

func testSeeder(handler roundTripFunc) *seeder {
	return &seeder{
		baseURL: "http://aperture.test",
		client:  &http.Client{Transport: handler},
		logger:  slog.New(slog.NewTextHandler(os.Stdout, nil)),
	}
}
