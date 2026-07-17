# ---- Build stage: compile the fat jar with Maven ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Cache dependencies first (only re-runs when pom.xml changes)
COPY pom.xml .
RUN mvn -q -B dependency:go-offline

# Build the application
COPY src ./src
RUN mvn -q -B clean package

# ---- Run stage: slim JRE image ----
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/bidtwenty.jar app.jar

# Render (and most PaaS) inject PORT; Main reads it, defaulting to 7070.
ENV PORT=7070
EXPOSE 7070

CMD ["java", "-jar", "app.jar"]
