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

	"gopkg.in/yaml.v3"
)

// ── Seed file model ────────────────────────────────────────────────────────────

type SeedFile struct {
	Tenants []TenantSeed `yaml:"tenants"`
}

type TenantSeed struct {
	Name              string             `yaml:"name"`
	Admin             Credential         `yaml:"admin"`
	Users             []UserSeed         `yaml:"users"`
	Suppliers         []Supplier         `yaml:"suppliers"`
	Products          []Product          `yaml:"products"`
	ServicePackages   []ServicePackage   `yaml:"servicePackages"`
	SubscriptionPlans []SubscriptionPlan `yaml:"subscriptionPlans"`
	Customers         []Customer         `yaml:"customers"`
	Invoices          []Invoice          `yaml:"invoices"`
}

type Credential struct {
	Username string `yaml:"username"`
	Password string `yaml:"password"`
}

type UserSeed struct {
	Username   string            `yaml:"username"`
	Password   string            `yaml:"password"`
	Roles      []string          `yaml:"roles"`
	Attributes map[string]string `yaml:"attributes"`
}

type Supplier struct {
	CompanyName string `yaml:"companyName"`
}

type Product struct {
	Name        string  `yaml:"name"`
	SKU         string  `yaml:"sku"`
	Category    string  `yaml:"category"`
	UnitPrice   float64 `yaml:"unitPrice"`
	Description string  `yaml:"description"`
}

type ServicePackage struct {
	Name        string  `yaml:"name"`
	SKU         string  `yaml:"sku"`
	UnitPrice   float64 `yaml:"unitPrice"`
	Description string  `yaml:"description"`
}

type SubscriptionPlan struct {
	Name            string  `yaml:"name"`
	SKU             string  `yaml:"sku"`
	UnitPrice       float64 `yaml:"unitPrice"`
	BillingInterval string  `yaml:"billingInterval"`
	Description     string  `yaml:"description"`
}

type Customer struct {
	Name  string `yaml:"name"`
	Email string `yaml:"email"`
}

type Invoice struct {
	CustomerRef string  `yaml:"customerRef"`
	Amount      float64 `yaml:"amount"`
	Status      string  `yaml:"status"`
}

// ── HTTP helpers ───────────────────────────────────────────────────────────────

type seeder struct {
	baseURL string
	client  *http.Client
	logger  *slog.Logger
}

func (s *seeder) post(path, token string, body interface{}) (map[string]interface{}, int, error) {
	b, _ := json.Marshal(body)
	req, _ := http.NewRequest(http.MethodPost, s.baseURL+path, bytes.NewReader(b))
	req.Header.Set("Content-Type", "application/json")
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	resp, err := s.client.Do(req)
	if err != nil {
		return nil, 0, err
	}
	defer resp.Body.Close()
	var result map[string]interface{}
	data, _ := io.ReadAll(resp.Body)
	_ = json.Unmarshal(data, &result)
	return result, resp.StatusCode, nil
}

func (s *seeder) postJSONAPI(path, token string, body interface{}) (map[string]interface{}, int, error) {
	b, _ := json.Marshal(body)
	req, _ := http.NewRequest(http.MethodPost, s.baseURL+path, bytes.NewReader(b))
	req.Header.Set("Content-Type", "application/vnd.api+json")
	req.Header.Set("Accept", "application/vnd.api+json")
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	resp, err := s.client.Do(req)
	if err != nil {
		return nil, 0, err
	}
	defer resp.Body.Close()
	var result map[string]interface{}
	data, _ := io.ReadAll(resp.Body)
	_ = json.Unmarshal(data, &result)
	return result, resp.StatusCode, nil
}

func (s *seeder) put(path, token string, body interface{}) (int, error) {
	b, _ := json.Marshal(body)
	req, _ := http.NewRequest(http.MethodPut, s.baseURL+path, bytes.NewReader(b))
	req.Header.Set("Content-Type", "application/json")
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	resp, err := s.client.Do(req)
	if err != nil {
		return 0, err
	}
	resp.Body.Close()
	return resp.StatusCode, nil
}

func (s *seeder) patch(path, token string, body interface{}) (int, error) {
	b, _ := json.Marshal(body)
	req, _ := http.NewRequest(http.MethodPatch, s.baseURL+path, bytes.NewReader(b))
	req.Header.Set("Content-Type", "application/json")
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	resp, err := s.client.Do(req)
	if err != nil {
		return 0, err
	}
	resp.Body.Close()
	return resp.StatusCode, nil
}

func (s *seeder) get(path, token string) (map[string]interface{}, int, error) {
	req, _ := http.NewRequest(http.MethodGet, s.baseURL+path, nil)
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	resp, err := s.client.Do(req)
	if err != nil {
		return nil, 0, err
	}
	defer resp.Body.Close()
	var result map[string]interface{}
	data, _ := io.ReadAll(resp.Body)
	_ = json.Unmarshal(data, &result)
	return result, resp.StatusCode, nil
}

// ── Wait for API ───────────────────────────────────────────────────────────────

func (s *seeder) waitForAPI(timeout time.Duration) error {
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		resp, err := s.client.Get(s.baseURL + "/actuator/health")
		if err == nil && resp.StatusCode == 200 {
			resp.Body.Close()
			s.logger.Info("API is healthy")
			return nil
		}
		if resp != nil {
			resp.Body.Close()
		}
		s.logger.Info("waiting for API...", "retry_in", "2s")
		time.Sleep(2 * time.Second)
	}
	return fmt.Errorf("API did not become healthy within %s", timeout)
}

// ── Login ──────────────────────────────────────────────────────────────────────

func (s *seeder) login(username, password string) (string, error) {
	result, status, err := s.post("/auth/login", "", map[string]string{
		"username": username,
		"password": password,
	})
	if err != nil {
		return "", err
	}
	if status != 200 {
		return "", fmt.Errorf("login failed for %s: status %d", username, status)
	}
	token, _ := result["accessToken"].(string)
	if token == "" {
		return "", fmt.Errorf("no accessToken in login response for %s", username)
	}
	return token, nil
}

// ── Seed a tenant ──────────────────────────────────────────────────────────────

func (s *seeder) seedTenant(t TenantSeed, superToken string) error {
	s.logger.Info("seeding tenant", "name", t.Name)

	// Provision tenant
	result, status, err := s.post("/manage/tenants", superToken, map[string]interface{}{
		"tenantName":             t.Name,
		"initialAdminUsername":   t.Admin.Username,
		"initialAdminPassword":   t.Admin.Password,
		"initialAdminAttributes": map[string]interface{}{"status": "active", "region": "eu"},
	})
	if err != nil {
		return fmt.Errorf("provision tenant %s: %w", t.Name, err)
	}
	if status != 201 && status != 409 {
		return fmt.Errorf("provision tenant %s: unexpected status %d", t.Name, status)
	}

	var tenantID string
	if status == 201 {
		if tenantObj, ok := result["tenant"].(map[string]interface{}); ok {
			tenantID, _ = tenantObj["id"].(string)
		}
		s.logger.Info("tenant provisioned", "name", t.Name, "id", tenantID)
	} else {
		s.logger.Info("tenant already exists, looking up ID", "name", t.Name)
		// Look up by listing tenants
		list, _, err := s.get("/manage/tenants", superToken)
		if err != nil {
			return fmt.Errorf("list tenants: %w", err)
		}
		for _, key := range []string{"items", "content"} {
			if items, ok := list[key].([]interface{}); ok {
				for _, item := range items {
					if m, ok := item.(map[string]interface{}); ok {
						if m["name"] == t.Name {
							tenantID, _ = m["id"].(string)
							break
						}
					}
				}
				if tenantID != "" {
					break
				}
			}
		}
	}

	// Login as tenant admin
	adminToken, err := s.login(t.Admin.Username, t.Admin.Password)
	if err != nil {
		return fmt.Errorf("login as tenant admin %s: %w", t.Admin.Username, err)
	}

	// Seed users
	for _, u := range t.Users {
		s.logger.Info("creating user", "username", u.Username)
		userResult, userStatus, err := s.post(
			fmt.Sprintf("/manage/tenants/%s/users", tenantID),
			superToken,
			map[string]string{"username": u.Username, "password": u.Password},
		)
		if err != nil {
			return fmt.Errorf("create user %s: %w", u.Username, err)
		}
		var userID string
		if userStatus == 201 {
			userID, _ = userResult["id"].(string)
		} else if userStatus == 409 {
			s.logger.Info("user already exists", "username", u.Username)
			continue
		} else {
			return fmt.Errorf("create user %s: status %d", u.Username, userStatus)
		}

		// Assign roles
		if len(u.Roles) > 0 {
			roleStatus, err := s.put(
				fmt.Sprintf("/manage/tenants/%s/users/%s/roles", tenantID, userID),
				superToken,
				map[string][]string{"roleNames": u.Roles},
			)
			if err != nil || (roleStatus != 200 && roleStatus != 204) {
				s.logger.Warn("assign roles failed", "user", u.Username, "status", roleStatus)
			}
		}

		// Set ABAC security attributes used by manifest policies such as ActiveUserOnly.
		if len(u.Attributes) > 0 {
			attrStatus, err := s.patch(
				fmt.Sprintf("/manage/tenants/%s/users/%s", tenantID, userID),
				superToken,
				map[string]interface{}{"securityAttributes": u.Attributes},
			)
			if err != nil || (attrStatus != 200 && attrStatus != 204) {
				s.logger.Warn("set attributes failed", "user", u.Username, "status", attrStatus)
			}
		}
	}

	// Seed suppliers
	for _, supplier := range t.Suppliers {
		s.logger.Info("creating supplier", "companyName", supplier.CompanyName)
		body := map[string]interface{}{
			"data": map[string]interface{}{
				"type": "suppliers",
				"attributes": map[string]interface{}{
					"company_name": supplier.CompanyName,
				},
			},
		}
		_, status, err := s.postJSONAPI("/api/v3/suppliers", adminToken, body)
		if err != nil {
			return fmt.Errorf("create supplier %s: %w", supplier.CompanyName, err)
		}
		if status == 201 {
			continue
		} else if status == 409 || status == 422 {
			s.logger.Info("supplier already exists or duplicate", "companyName", supplier.CompanyName)
		} else {
			return fmt.Errorf("create supplier %s: status %d", supplier.CompanyName, status)
		}
	}

	// Seed products
	productIDByName := map[string]string{}
	for _, p := range t.Products {
		s.logger.Info("creating product", "name", p.Name, "sku", p.SKU)
		body := map[string]interface{}{
			"data": map[string]interface{}{
				"type": "products",
				"attributes": map[string]interface{}{
					"name":        p.Name,
					"sku":         p.SKU,
					"category":    p.Category,
					"unit_price":  p.UnitPrice,
					"description": p.Description,
					"active":      true,
				},
			},
		}
		result, status, err := s.postJSONAPI("/api/v3/products", adminToken, body)
		if err != nil {
			return fmt.Errorf("create product %s: %w", p.Name, err)
		}
		if status == 201 {
			if d, ok := result["data"].(map[string]interface{}); ok {
				productIDByName[p.Name], _ = d["id"].(string)
			}
		} else if status == 409 || status == 422 {
			s.logger.Info("product already exists or duplicate", "name", p.Name)
		} else {
			return fmt.Errorf("create product %s: status %d", p.Name, status)
		}
		_ = productIDByName
	}

	// Seed service packages
	for _, servicePackage := range t.ServicePackages {
		s.logger.Info("creating service package", "name", servicePackage.Name, "sku", servicePackage.SKU)
		body := map[string]interface{}{
			"data": map[string]interface{}{
				"type": "servicepackages",
				"attributes": map[string]interface{}{
					"name":        servicePackage.Name,
					"sku":         servicePackage.SKU,
					"unit_price":  servicePackage.UnitPrice,
					"description": servicePackage.Description,
					"active":      true,
				},
			},
		}
		_, status, err := s.postJSONAPI("/api/v3/servicepackages", adminToken, body)
		if err != nil {
			return fmt.Errorf("create service package %s: %w", servicePackage.Name, err)
		}
		if status == 201 {
			continue
		} else if status == 409 || status == 422 {
			s.logger.Info("service package already exists or duplicate", "name", servicePackage.Name)
		} else {
			return fmt.Errorf("create service package %s: status %d", servicePackage.Name, status)
		}
	}

	// Seed subscription plans
	for _, plan := range t.SubscriptionPlans {
		s.logger.Info("creating subscription plan", "name", plan.Name, "sku", plan.SKU)
		body := map[string]interface{}{
			"data": map[string]interface{}{
				"type": "subscriptionplans",
				"attributes": map[string]interface{}{
					"name":             plan.Name,
					"sku":              plan.SKU,
					"unit_price":       plan.UnitPrice,
					"billing_interval": plan.BillingInterval,
					"description":      plan.Description,
					"active":           true,
				},
			},
		}
		_, status, err := s.postJSONAPI("/api/v3/subscriptionplans", adminToken, body)
		if err != nil {
			return fmt.Errorf("create subscription plan %s: %w", plan.Name, err)
		}
		if status == 201 {
			continue
		} else if status == 409 || status == 422 {
			s.logger.Info("subscription plan already exists or duplicate", "name", plan.Name)
		} else {
			return fmt.Errorf("create subscription plan %s: status %d", plan.Name, status)
		}
	}

	// Seed customers
	customerIDByName := map[string]string{}
	for _, c := range t.Customers {
		s.logger.Info("creating customer", "name", c.Name)
		body := map[string]interface{}{
			"data": map[string]interface{}{
				"type": "customers",
				"attributes": map[string]interface{}{
					"name":  c.Name,
					"email": c.Email,
				},
			},
		}
		result, status, err := s.postJSONAPI("/api/v3/customers", adminToken, body)
		if err != nil {
			return fmt.Errorf("create customer %s: %w", c.Name, err)
		}
		if status == 201 {
			if d, ok := result["data"].(map[string]interface{}); ok {
				customerIDByName[c.Name], _ = d["id"].(string)
			}
		} else if status == 409 || status == 422 {
			s.logger.Info("customer already exists", "name", c.Name)
		} else {
			return fmt.Errorf("create customer %s: status %d", c.Name, status)
		}
	}

	// Seed invoices
	for _, inv := range t.Invoices {
		customerID, ok := customerIDByName[inv.CustomerRef]
		if !ok {
			s.logger.Warn("customer not found for invoice, skipping", "customerRef", inv.CustomerRef)
			continue
		}
		s.logger.Info("creating invoice", "customerRef", inv.CustomerRef, "amount", inv.Amount)
		body := map[string]interface{}{
			"data": map[string]interface{}{
				"type": "invoices",
				"attributes": map[string]interface{}{
					"amount": inv.Amount,
					"status": inv.Status,
				},
				"relationships": map[string]interface{}{
					"customer": map[string]interface{}{
						"data": map[string]interface{}{
							"type": "customers",
							"id":   customerID,
						},
					},
				},
			},
		}
		_, status, err := s.postJSONAPI("/api/v3/invoices", adminToken, body)
		if err != nil {
			return fmt.Errorf("create invoice: %w", err)
		}
		if status != 201 && status != 409 && status != 422 {
			return fmt.Errorf("create invoice: status %d", status)
		}
	}

	s.logger.Info("tenant seeded successfully", "name", t.Name)
	return nil
}

// ── Main ───────────────────────────────────────────────────────────────────────

func main() {
	seedFile := flag.String("seed-file", envOrDefault("SEED_FILE", "seed.yaml"), "path to seed YAML file")
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

	// Read seed file
	data, err := os.ReadFile(*seedFile)
	if err != nil {
		logger.Error("failed to read seed file", "path", *seedFile, "err", err)
		os.Exit(1)
	}
	var seed SeedFile
	if err := yaml.Unmarshal(data, &seed); err != nil {
		logger.Error("failed to parse seed file", "err", err)
		os.Exit(1)
	}

	// Wait for API
	if err := s.waitForAPI(60 * time.Second); err != nil {
		logger.Error("API health check failed", "err", err)
		os.Exit(1)
	}

	// Login as superadmin
	superToken, err := s.login(*superUser, *superPass)
	if err != nil {
		logger.Error("superadmin login failed", "err", err)
		os.Exit(1)
	}
	logger.Info("superadmin login successful")

	// Seed each tenant
	for _, t := range seed.Tenants {
		if err := s.seedTenant(t, superToken); err != nil {
			logger.Error("failed to seed tenant", "name", t.Name, "err", err)
			os.Exit(1)
		}
	}

	logger.Info("seeding complete")
}

func envOrDefault(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}
