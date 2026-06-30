FROM golang:1.26.4-alpine AS builder
WORKDIR /app
COPY . .
RUN go build -o hook-service main.go

FROM alpine:3.22.0
WORKDIR /app
COPY --from=builder /app/hook-service .
EXPOSE 8080
CMD ["./hook-service"]
