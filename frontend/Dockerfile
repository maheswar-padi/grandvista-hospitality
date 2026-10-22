# syntax=docker/dockerfile:1.7
# ---- build stage ----
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY . .
RUN npm run build

# ---- runtime stage: static files served by unprivileged nginx (runs as uid 101 on port 8080) ----
FROM nginxinc/nginx-unprivileged:1.27-alpine
# The nginx entrypoint renders templates/*.template into conf.d/ with envsubst; only ${CSP_*} variables appear.
ENV NGINX_ENVSUBST_FILTER=^CSP_
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
COPY --chmod=0755 docker/15-payment-csp.envsh /docker-entrypoint.d/15-payment-csp.envsh
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --retries=5 CMD wget -q -O /dev/null http://127.0.0.1:8080/healthz || exit 1
