# Build stage
FROM maven:3.8.4-openjdk-11-slim AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

# Runtime stage
FROM openjdk:11-jre-slim
WORKDIR /app

# Install curl for health check
RUN apt-get update && apt-get install -y curl && rm -rf /var/lib/apt/lists/*

# Copy the built JAR
COPY --from=build /app/target/discord-selfbot.jar /app/discord-selfbot.jar

# Create non-root user
RUN useradd -m -u 1001 discordbot
USER 1001

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
  CMD curl -f http://localhost:8080/health || exit 1

# Expose port (for health check)
EXPOSE 8080

# Start the application
ENTRYPOINT ["java", "-jar", "discord-selfbot.jar"]