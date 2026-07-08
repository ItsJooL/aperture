package main

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"os"
	"regexp"
	"strings"
	"time"

	"go.opentelemetry.io/contrib/instrumentation/net/http/otelhttp"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracehttp"
	"go.opentelemetry.io/otel/propagation"
	"go.opentelemetry.io/otel/sdk/resource"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	semconv "go.opentelemetry.io/otel/semconv/v1.21.0"
)

func initTracer() *sdktrace.TracerProvider {
	ctx := context.Background()
	exp, err := otlptracehttp.New(ctx, otlptracehttp.WithInsecure(), otlptracehttp.WithEndpoint("jaeger:4318"))
	if err != nil {
		slog.Error("failed to create trace exporter", "err", err)
		return nil
	}
	res, _ := resource.New(ctx, resource.WithAttributes(semconv.ServiceNameKey.String("aperture-demo-hook-service")))
	tp := sdktrace.NewTracerProvider(
		sdktrace.WithBatcher(exp),
		sdktrace.WithResource(res),
	)
	otel.SetTracerProvider(tp)
	otel.SetTextMapPropagator(propagation.TraceContext{})
	return tp
}

// Hook payload is the flat Jackson serialization of the Elide entity — all
// entity fields appear at the top level (not wrapped in data.attributes).
type HookPayload struct {
	Fields map[string]interface{}
}

func readPayload(r *http.Request) (*HookPayload, error) {
	body, err := io.ReadAll(r.Body)
	if err != nil {
		return nil, err
	}
	var fields map[string]interface{}
	if err := json.Unmarshal(body, &fields); err != nil {
		return nil, err
	}
	return &HookPayload{Fields: fields}, nil
}

func reject(w http.ResponseWriter, reason string, status int) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(map[string]string{"error": reason})
}

func approve(w http.ResponseWriter) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	json.NewEncoder(w).Encode(map[string]string{"status": "approved"})
}

func withLogging(logger *slog.Logger, h http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		if tp := r.Header.Get("traceparent"); tp != "" {
			w.Header().Set("traceparent", tp)
		}
		h(w, r)
		logger.Info("hook invoked",
			"path", r.URL.Path,
			"method", r.Method,
			"duration_ms", time.Since(start).Milliseconds())
	}
}

func validateInvoice(w http.ResponseWriter, r *http.Request) {
	p, err := readPayload(r)
	if err != nil || len(p.Fields) == 0 {
		approve(w)
		return
	}
	if amt, ok := p.Fields["amount"]; ok {
		var amount float64
		switch v := amt.(type) {
		case float64:
			amount = v
		case int:
			amount = float64(v)
		}
		if amount <= 0 {
			reject(w, "amount must be positive", http.StatusUnprocessableEntity)
			return
		}
	}
	approve(w)
}

func validatePayment(w http.ResponseWriter, r *http.Request) {
	p, err := readPayload(r)
	if err != nil {
		reject(w, "invalid payload", http.StatusBadRequest)
		return
	}
	if amt, ok := p.Fields["amount"]; ok {
		var amount float64
		switch v := amt.(type) {
		case float64:
			amount = v
		case int:
			amount = float64(v)
		}
		if amount <= 0 {
			reject(w, "amount must be positive", http.StatusUnprocessableEntity)
			return
		}
	}
	approve(w)
}

var skuRegex = regexp.MustCompile(`^[A-Z0-9-]{3,20}$`)

func validateProduct(w http.ResponseWriter, r *http.Request) {
	p, err := readPayload(r)
	if err != nil {
		reject(w, "invalid payload", http.StatusBadRequest)
		return
	}
	price, _ := p.Fields["unit_price"].(float64)
	if price <= 0 {
		reject(w, "unit_price must be positive", http.StatusUnprocessableEntity)
		return
	}
	sku, _ := p.Fields["sku"].(string)
	if !skuRegex.MatchString(sku) {
		reject(w, "sku must be 3-20 uppercase alphanumeric characters or hyphens", http.StatusUnprocessableEntity)
		return
	}
	approve(w)
}

func validateCustomerEmail(w http.ResponseWriter, r *http.Request) {
	p, err := readPayload(r)
	if err != nil {
		reject(w, "invalid payload", http.StatusBadRequest)
		return
	}
	email, _ := p.Fields["email"].(string)
	if email != "" && !strings.Contains(email, "@") {
		reject(w, "invalid email address", http.StatusUnprocessableEntity)
		return
	}
	approve(w)
}

// enrichCustomer is a PREENRICH hook — it normalizes the customer name and
// returns attribute overrides. The framework applies them before persisting.
func enrichCustomer(logger *slog.Logger) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		p, err := readPayload(r)
		if err != nil || len(p.Fields) == 0 {
			// passthrough: no enrichment applied
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusOK)
			json.NewEncoder(w).Encode(map[string]interface{}{})
			return
		}
		name, _ := p.Fields["name"].(string)
		email, _ := p.Fields["email"].(string)

		// Normalize: trim whitespace and title-case the name
		normalizedName := strings.TrimSpace(name)
		if normalizedName == "" {
			normalizedName = name
		}

		masked := email
		if idx := strings.Index(email, "@"); idx > 0 {
			masked = strings.Repeat("*", idx) + email[idx:]
		}
		logger.Info("customer enrichment", "name", normalizedName, "email_masked", masked)

		// Return attribute overrides — the framework applies them before persisting
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(map[string]interface{}{
			"data": map[string]interface{}{
				"attributes": map[string]interface{}{
					"name": normalizedName,
				},
			},
		})
	}
}

func notifySupplier(logger *slog.Logger) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		time.Sleep(50 * time.Millisecond)
		logger.Info("supplier notification dispatched (simulated)")
		approve(w)
	}
}

func productChanged(logger *slog.Logger) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		p, _ := readPayload(r)
		if p != nil {
			logger.Info("product change event",
				"sku", p.Fields["sku"],
				"category", p.Fields["category"],
				"unit_price", p.Fields["unit_price"])
		}
		approve(w)
	}
}

func tenantProvisioned(logger *slog.Logger) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		p, _ := readPayload(r)
		if p != nil {
			logger.Info("tenant provisioned — sending welcome email (simulated)",
				"fields", p.Fields)
		}
		approve(w)
	}
}

func passthrough(w http.ResponseWriter, r *http.Request) {
	approve(w)
}

func main() {
	logger := slog.New(slog.NewJSONHandler(os.Stdout, nil))

	tp := initTracer()
	if tp != nil {
		defer func() { _ = tp.Shutdown(context.Background()) }()
	}

	mux := http.NewServeMux()
	mux.HandleFunc("/health", func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
		fmt.Fprint(w, "ok")
	})
	mux.HandleFunc("/hooks/validate-invoice", withLogging(logger, validateInvoice))
	mux.HandleFunc("/hooks/validate-payment", withLogging(logger, validatePayment))
	mux.HandleFunc("/hooks/validate-product", withLogging(logger, validateProduct))
	mux.HandleFunc("/hooks/validate-customer-email", withLogging(logger, validateCustomerEmail))
	mux.HandleFunc("/hooks/enrich-customer", withLogging(logger, enrichCustomer(logger)))
	mux.HandleFunc("/hooks/notify-supplier", withLogging(logger, notifySupplier(logger)))
	mux.HandleFunc("/hooks/product-changed", withLogging(logger, productChanged(logger)))
	mux.HandleFunc("/hooks/tenant-provisioned", withLogging(logger, tenantProvisioned(logger)))
	mux.HandleFunc("/hooks/check-line-item", withLogging(logger, passthrough))
	mux.HandleFunc("/hooks/audit-country", withLogging(logger, passthrough))

	logger.Info("hook service starting", "port", 8080)
	handler := otelhttp.NewHandler(mux, "hook-service")
	if err := http.ListenAndServe(":8080", handler); err != nil {
		logger.Error("server failed", "err", err)
	}
}
