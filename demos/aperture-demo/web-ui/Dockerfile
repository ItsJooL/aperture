# Multi‑stage Dockerfile for the Vue UI demo
# -------------------------------------------------
# Stage 1 – Build the Vue app
# -------------------------------------------------
FROM node:20-alpine AS builder
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
# Ensure type‑checking passes before building
RUN npm run typecheck && npm run build

# -------------------------------------------------
# Stage 2 – Serve the static assets with nginx
# -------------------------------------------------
FROM nginx:alpine
# Remove default static files
RUN rm -rf /usr/share/nginx/html/*
# Copy the built assets from the builder stage
COPY --from=builder /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
