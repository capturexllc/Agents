FROM node:22-alpine AS frontend-build
WORKDIR /workspace
COPY package.json package-lock.json ./
RUN npm ci
COPY index.html vite.config.ts tsconfig.json ./
COPY src ./src
ARG VITE_AUTH_REQUIRED=false
ENV VITE_AUTH_REQUIRED=${VITE_AUTH_REQUIRED}
RUN npm run build

FROM maven:3.9.9-eclipse-temurin-21-alpine AS backend-build
WORKDIR /workspace
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline
COPY src ./src
COPY --from=frontend-build /workspace/dist ./src/main/resources/static
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S -G app -u 10001 app
COPY --from=backend-build --chown=app:app /workspace/target/vamsdest-0.1.0-SNAPSHOT.jar /app/app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-XX:+ExitOnOutOfMemoryError", "-Djava.io.tmpdir=/tmp", "-jar", "/app/app.jar"]