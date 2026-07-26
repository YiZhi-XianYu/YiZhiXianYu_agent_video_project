# ── Stage 1: Build Vue frontend ──
FROM node:22-alpine AS frontend-builder
WORKDIR /web-app
COPY web-app/package*.json ./
RUN npm ci
COPY web-app/ ./
RUN npm run build -- --outDir /frontend-dist

# ── Stage 2: Build Java application ──
FROM maven:3.9-eclipse-temurin-21-alpine AS builder
WORKDIR /build
COPY control-plane/pom.xml .
RUN mvn dependency:go-offline -q
COPY control-plane/src/ src/
RUN rm -rf src/main/resources/static && mkdir -p src/main/resources/static
COPY --from=frontend-builder /frontend-dist/ src/main/resources/static/
RUN mvn package -DskipTests -q

# ── Stage 3: Runtime ──
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app \
    && adduser -S app -G app \
    && apk add --no-cache su-exec
WORKDIR /app
COPY --from=builder /build/target/*.jar app.jar
COPY control-plane/docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
RUN chmod 0755 /usr/local/bin/docker-entrypoint.sh
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/docker-entrypoint.sh"]
CMD ["java", "-jar", "/app/app.jar"]
