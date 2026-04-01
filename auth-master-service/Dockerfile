# Stage 1: Build the specific module
# Change 'alpine' to 'jammy'
FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /app

COPY . .
RUN ./gradlew :auth-master-service:bootJar -x test --no-daemon

# Stage 2: Lightweight runtime image
# Change 'alpine' to 'jammy'
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# Non-root user setup (Ubuntu commands are slightly different than Alpine)
RUN useradd -ms /bin/bash spring
USER spring

COPY --from=build /app/auth-master-service/build/libs/auth-master-service-*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
