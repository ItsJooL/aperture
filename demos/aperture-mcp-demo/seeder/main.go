package main

import (
	"bytes"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"os"
	"time"
)

type Project struct {
	Name        string
	Description string
}

type Task struct {
	Title     string
	Notes     string
	Status    string
	ProjectID string
}

type seeder struct {
	baseURL string
	client  *http.Client
	logger  *slog.Logger
}

func main() {
	apiURL := flag.String("api-url", envOrDefault("API_URL", "http://api-server:8080"), "Aperture API base URL")
	superUser := flag.String("superadmin-username", envOrDefault("SUPERADMIN_USERNAME", "superadmin@framework.local"), "superadmin username")
	superPass := flag.String("superadmin-password", envOrDefault("SUPERADMIN_PASSWORD", "changeme-local-only"), "superadmin password")
	flag.Parse()

	logger := slog.New(slog.NewJSONHandler(os.Stdout, nil))
	s := &seeder{
		baseURL: *apiURL,
		client:  &http.Client{Timeout: 10 * time.Second},
		logger:  logger,
	}

	if err := s.waitForAPI(60 * time.Second); err != nil {
		logger.Error("API health check failed", "err", err)
		os.Exit(1)
	}

	token, err := s.login(*superUser, *superPass)
	if err != nil {
		logger.Error("superadmin login failed", "err", err)
		os.Exit(1)
	}

	projectID, err := s.createProject(token, Project{
		Name:        "MCP Demo Launch",
		Description: "A small project seeded so MCP clients can list, inspect, and update real Aperture data.",
	})
	if err != nil {
		logger.Error("project seed failed", "err", err)
		os.Exit(1)
	}

	tasks := []Task{
		{
			Title:     "Verify generated tools",
			Notes:     "Call tools/list and confirm Project and Task operations are available.",
			Status:    "DONE",
			ProjectID: projectID,
		},
		{
			Title:     "Create a task through MCP",
			Notes:     "Use tools/call with create_task to add another task to this project.",
			Status:    "DOING",
			ProjectID: projectID,
		},
		{
			Title:     "Compare MCP and JSON:API output",
			Notes:     "List tasks through both surfaces and confirm they enforce the same permissions.",
			Status:    "TODO",
			ProjectID: projectID,
		},
	}
	for _, task := range tasks {
		if err := s.createTask(token, task); err != nil {
			logger.Error("task seed failed", "title", task.Title, "err", err)
			os.Exit(1)
		}
	}

	logger.Info("mcp demo seed complete", "project_id", projectID, "tasks", len(tasks))
}

func (s *seeder) waitForAPI(timeout time.Duration) error {
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		resp, err := s.client.Get(s.baseURL + "/actuator/health")
		if err == nil && resp.StatusCode == http.StatusOK {
			resp.Body.Close()
			s.logger.Info("API is healthy")
			return nil
		}
		if resp != nil {
			resp.Body.Close()
		}
		s.logger.Info("waiting for API", "retry_in", "2s")
		time.Sleep(2 * time.Second)
	}
	return fmt.Errorf("API did not become healthy within %s", timeout)
}

func (s *seeder) login(username, password string) (string, error) {
	result, status, err := s.post("/auth/login", "", "application/json", map[string]string{
		"username": username,
		"password": password,
	})
	if err != nil {
		return "", err
	}
	if status != http.StatusOK {
		return "", fmt.Errorf("login failed for %s: status %d", username, status)
	}
	token, _ := result["accessToken"].(string)
	if token == "" {
		return "", fmt.Errorf("no accessToken in login response for %s", username)
	}
	return token, nil
}

func (s *seeder) createProject(token string, project Project) (string, error) {
	result, status, err := s.post("/api/v1/projects", token, "application/vnd.api+json", newProjectRequest(project))
	if err != nil {
		return "", fmt.Errorf("create project %q: %w", project.Name, err)
	}
	if status != http.StatusCreated {
		return "", fmt.Errorf("create project %q: status %d", project.Name, status)
	}
	id, err := extractResourceID(result)
	if err != nil {
		return "", fmt.Errorf("create project %q: %w", project.Name, err)
	}
	s.logger.Info("project seeded", "name", project.Name, "id", id)
	return id, nil
}

func (s *seeder) createTask(token string, task Task) error {
	_, status, err := s.post("/api/v1/tasks", token, "application/vnd.api+json", newTaskRequest(task))
	if err != nil {
		return fmt.Errorf("create task %q: %w", task.Title, err)
	}
	if status != http.StatusCreated {
		return fmt.Errorf("create task %q: status %d", task.Title, status)
	}
	s.logger.Info("task seeded", "title", task.Title, "status", task.Status)
	return nil
}

func (s *seeder) post(path, token, contentType string, body interface{}) (map[string]interface{}, int, error) {
	b, err := json.Marshal(body)
	if err != nil {
		return nil, 0, fmt.Errorf("marshal request: %w", err)
	}
	req, err := http.NewRequest(http.MethodPost, s.baseURL+path, bytes.NewReader(b))
	if err != nil {
		return nil, 0, fmt.Errorf("build request: %w", err)
	}
	req.Header.Set("Content-Type", contentType)
	if contentType == "application/vnd.api+json" {
		req.Header.Set("Accept", "application/vnd.api+json")
	}
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	resp, err := s.client.Do(req)
	if err != nil {
		return nil, 0, err
	}
	defer resp.Body.Close()
	data, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, resp.StatusCode, fmt.Errorf("read response: %w", err)
	}
	var result map[string]interface{}
	if len(data) > 0 {
		_ = json.Unmarshal(data, &result)
	}
	return result, resp.StatusCode, nil
}

func newProjectRequest(project Project) map[string]interface{} {
	return map[string]interface{}{
		"data": map[string]interface{}{
			"type": "projects",
			"attributes": map[string]interface{}{
				"name":        project.Name,
				"description": project.Description,
			},
		},
	}
}

func newTaskRequest(task Task) map[string]interface{} {
	return map[string]interface{}{
		"data": map[string]interface{}{
			"type": "tasks",
			"attributes": map[string]interface{}{
				"title":  task.Title,
				"notes":  task.Notes,
				"status": task.Status,
			},
			"relationships": map[string]interface{}{
				"project": map[string]interface{}{
					"data": map[string]interface{}{
						"type": "projects",
						"id":   task.ProjectID,
					},
				},
			},
		},
	}
}

func extractResourceID(response map[string]interface{}) (string, error) {
	data, ok := response["data"].(map[string]interface{})
	if !ok {
		return "", fmt.Errorf("response did not include JSON:API data object")
	}
	id, _ := data["id"].(string)
	if id == "" {
		return "", fmt.Errorf("response data did not include id")
	}
	return id, nil
}

func envOrDefault(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}
