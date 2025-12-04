# Multi-stage build for Railway
# Build stage
FROM maven:3.8.5-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

# Runtime stage
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Install curl for health check
RUN apk add --no-cache curl

# Copy the built JAR
COPY --from=build /app/target/discord-selfbot.jar /app/discord-selfbot.jar

# Create non-root user
RUN adduser -D -u 1001 discordbot
USER discordbot

# Health check endpoint
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
  CMD curl -f http://localhost:8080/health || exit 1

# Expose port for health checks
EXPOSE 8080

# Start the application
ENTRYPOINT ["java", "-jar", "discord-selfbot.jar"]
