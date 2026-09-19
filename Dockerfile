FROM eclipse-temurin:8-jre-alpine

WORKDIR /app

COPY target/client-server-messaging-relay-1.0-SNAPSHOT.jar app.jar

EXPOSE 8080

CMD ["java", "-jar", "app.jar"]