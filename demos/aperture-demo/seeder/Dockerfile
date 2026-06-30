FROM golang:1.26.4-alpine AS builder
WORKDIR /app
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN go build -o seeder .

FROM alpine:latest
WORKDIR /app
COPY --from=builder /app/seeder .
COPY seed.yaml .
ENTRYPOINT ["./seeder"]
