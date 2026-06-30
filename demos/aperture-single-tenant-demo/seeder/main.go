package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"time"
)

func main() {
	apiURL := getEnv("API_URL", "http://localhost:8080")
	password := getEnv("SUPERADMIN_PASSWORD", "changeme-local-only")

	token := mustLogin(apiURL, "superadmin@framework.local", password)
	createNote(apiURL, token, "Welcome note", "This is the aperture-single-tenant-demo. No tenants required.")
	createNote(apiURL, token, "NONE mode demo", "Schema has no aperture_tenant_id columns in domain tables.")
	fmt.Println("Seeding complete.")
}

func mustLogin(apiURL, username, password string) string {
	body := fmt.Sprintf(`{"username":%q,"password":%q}`, username, password)
	resp := mustPost(apiURL+"/auth/login", "application/json", body, "")
	var result map[string]interface{}
	if err := json.Unmarshal(resp, &result); err != nil {
		fmt.Fprintf(os.Stderr, "Failed to parse login response: %v\n%s\n", err, resp)
		os.Exit(1)
	}
	token, ok := result["accessToken"].(string)
	if !ok || token == "" {
		fmt.Fprintf(os.Stderr, "No accessToken in login response: %s\n", resp)
		os.Exit(1)
	}
	return "Bearer " + token
}

func createNote(apiURL, token, title, content string) {
	body := fmt.Sprintf(`{"data":{"type":"note","attributes":{"title":%q,"content":%q}}}`, title, content)
	mustPost(apiURL+"/api/v1/note", "application/vnd.api+json", body, token)
	fmt.Printf("Created note: %s\n", title)
}

func mustPost(url, contentType, body, token string) []byte {
	client := &http.Client{Timeout: 30 * time.Second}
	req, err := http.NewRequest("POST", url, bytes.NewBufferString(body))
	if err != nil {
		fmt.Fprintf(os.Stderr, "Failed to build request to %s: %v\n", url, err)
		os.Exit(1)
	}
	req.Header.Set("Content-Type", contentType)
	if token != "" {
		req.Header.Set("Authorization", token)
	}
	resp, err := client.Do(req)
	if err != nil {
		fmt.Fprintf(os.Stderr, "Request to %s failed: %v\n", url, err)
		os.Exit(1)
	}
	defer resp.Body.Close()
	data, _ := io.ReadAll(resp.Body)
	if resp.StatusCode >= 400 {
		fmt.Fprintf(os.Stderr, "POST %s returned %d: %s\n", url, resp.StatusCode, data)
		os.Exit(1)
	}
	return data
}

func getEnv(key, defaultVal string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return defaultVal
}
