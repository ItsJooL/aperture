package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"strings"
	"time"
)

func main() {
	apiURL := getEnv("API_URL", "http://localhost:8080")
	keycloakURL := getEnv("KEYCLOAK_URL", "http://localhost:8180")
	realm := getEnv("KEYCLOAK_REALM", "aperture")
	clientID := getEnv("KEYCLOAK_CLIENT_ID", "aperture-api")
	adminUser := getEnv("ADMIN_USERNAME", "admin@keycloak-demo.com")
	adminPass := getEnv("ADMIN_PASSWORD", "Admin123!")

	token := mustGetKeycloakToken(keycloakURL, realm, clientID, adminUser, adminPass)
	createProduct(apiURL, token, "Widget Pro", "99.99", "WGT-001")
	createProduct(apiURL, token, "Gadget Plus", "149.99", "GDG-002")
	fmt.Println("Seeding complete.")
}

func mustGetKeycloakToken(baseURL, realm, clientID, username, password string) string {
	tokenURL := baseURL + "/realms/" + realm + "/protocol/openid-connect/token"
	data := url.Values{
		"grant_type": {"password"},
		"client_id":  {clientID},
		"username":   {username},
		"password":   {password},
	}
	resp, err := http.PostForm(tokenURL, data)
	if err != nil {
		fmt.Fprintf(os.Stderr, "Keycloak token request failed: %v\n", err)
		os.Exit(1)
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != 200 {
		fmt.Fprintf(os.Stderr, "Keycloak token endpoint returned %d: %s\n", resp.StatusCode, body)
		os.Exit(1)
	}
	var result map[string]interface{}
	if err := json.Unmarshal(body, &result); err != nil {
		fmt.Fprintf(os.Stderr, "Failed to parse token response: %v\n", err)
		os.Exit(1)
	}
	token, ok := result["access_token"].(string)
	if !ok || token == "" {
		fmt.Fprintf(os.Stderr, "No access_token in Keycloak response: %s\n", body)
		os.Exit(1)
	}
	return "Bearer " + token
}

func createProduct(apiURL, token, name, price, sku string) {
	body := fmt.Sprintf(
		`{"data":{"type":"products","attributes":{"name":%q,"price":%s,"sku":%q}}}`,
		name, price, sku)
	mustPost(apiURL+"/api/v1/products", "application/vnd.api+json", body, token)
	fmt.Printf("Created product: %s\n", name)
}

func mustPost(endpoint, contentType, body, token string) []byte {
	client := &http.Client{Timeout: 30 * time.Second}
	req, err := http.NewRequest("POST", endpoint, bytes.NewBufferString(body))
	if err != nil {
		fmt.Fprintf(os.Stderr, "Failed to build request to %s: %v\n", endpoint, err)
		os.Exit(1)
	}
	req.Header.Set("Content-Type", contentType)
	if token != "" {
		req.Header.Set("Authorization", token)
	}
	resp, err := client.Do(req)
	if err != nil {
		fmt.Fprintf(os.Stderr, "Request to %s failed: %v\n", endpoint, err)
		os.Exit(1)
	}
	defer resp.Body.Close()
	data, _ := io.ReadAll(resp.Body)
	if resp.StatusCode >= 400 {
		fmt.Fprintf(os.Stderr, "POST %s returned %d: %s\n", endpoint, resp.StatusCode, data)
		os.Exit(1)
	}
	return data
}

func getEnv(key, defaultVal string) string {
	if v := strings.TrimSpace(os.Getenv(key)); v != "" {
		return v
	}
	return defaultVal
}
