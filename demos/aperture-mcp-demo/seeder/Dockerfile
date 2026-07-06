FROM golang:1.26-alpine AS builder
WORKDIR /src
COPY go.mod ./
COPY *.go ./
RUN go test ./... && go build -o /out/seeder .

FROM alpine:3.22
WORKDIR /app
COPY --from=builder /out/seeder /app/seeder
ENTRYPOINT ["/app/seeder"]
