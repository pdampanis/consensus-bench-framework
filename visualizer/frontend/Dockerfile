# =============================================================================
# Consensus Visualizer frontend — React + Vite, served by nginx
# =============================================================================

# -------- stage 1: build ------------------------------------------------------
FROM node:20-alpine AS build
WORKDIR /src

# Copy manifest first for layer caching
COPY package.json ./
RUN npm install --no-audit --no-fund

# Copy source and build
COPY tsconfig.json vite.config.ts index.html ./
COPY src ./src
RUN npm run build

# -------- stage 2: runtime ----------------------------------------------------
FROM nginx:1.27-alpine
# Our own nginx config proxies /api to the backend container
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/dist /usr/share/nginx/html
EXPOSE 80
HEALTHCHECK --interval=15s --timeout=3s --retries=5 \
  CMD wget -q --spider http://localhost/ || exit 1
