package main

import (
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"strings"
	"testing"
)

type roundTripFunc func(*http.Request) (*http.Response, error)

func (f roundTripFunc) RoundTrip(request *http.Request) (*http.Response, error) {
	return f(request)
}

func TestSeedCatalogResourcePostsJSONAPIAndAcceptsDuplicate(t *testing.T) {
	var requestBody map[string]interface{}
	client := &http.Client{Transport: roundTripFunc(func(r *http.Request) (*http.Response, error) {
		if r.URL.Path != "/api/v3/servicepackages" {
			t.Fatalf("path = %q", r.URL.Path)
		}
		if got := r.Header.Get("Content-Type"); got != "application/vnd.api+json" {
			t.Fatalf("Content-Type = %q", got)
		}
		if err := json.NewDecoder(r.Body).Decode(&requestBody); err != nil {
			t.Fatal(err)
		}
		return &http.Response{
			StatusCode: http.StatusConflict,
			Body:       io.NopCloser(strings.NewReader(`{}`)),
			Header:     make(http.Header),
		}, nil
	})}

	s := &seeder{baseURL: "http://example.test", client: client, logger: slog.New(slog.NewTextHandler(io.Discard, nil))}
	err := s.seedCatalogResource("token", catalogResource{
		kind:         "service package",
		resourceType: "servicepackages",
		path:         "/api/v3/servicepackages",
		name:         "Priority Support",
		sku:          "SUPPORT-PRIORITY",
		attributes: map[string]interface{}{
			"unit_price": 49.0,
			"active":     true,
		},
	})

	if err != nil {
		t.Fatalf("seedCatalogResource() error = %v", err)
	}
	data := requestBody["data"].(map[string]interface{})
	if data["type"] != "servicepackages" {
		t.Fatalf("data.type = %v", data["type"])
	}
	attributes := data["attributes"].(map[string]interface{})
	if attributes["name"] != "Priority Support" || attributes["sku"] != "SUPPORT-PRIORITY" {
		t.Fatalf("attributes = %#v", attributes)
	}
}

func TestSeedCatalogResourceRejectsUnexpectedStatus(t *testing.T) {
	client := &http.Client{Transport: roundTripFunc(func(*http.Request) (*http.Response, error) {
		return &http.Response{
			StatusCode: http.StatusBadGateway,
			Body:       io.NopCloser(strings.NewReader(`{}`)),
			Header:     make(http.Header),
		}, nil
	})}
	s := &seeder{baseURL: "http://example.test", client: client, logger: slog.New(slog.NewTextHandler(io.Discard, nil))}
	err := s.seedCatalogResource("token", catalogResource{
		kind: "subscription plan", resourceType: "subscriptionplans", path: "/plans", name: "Growth",
	})

	if err == nil || err.Error() != "create subscription plan Growth: status 502" {
		t.Fatalf("error = %v", err)
	}
}
