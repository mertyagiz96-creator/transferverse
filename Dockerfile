FROM eclipse-temurin:17-jdk-alpine AS build
WORKDIR /app
COPY . .
RUN chmod +x gradlew
RUN ./gradlew shadowJar --no-daemon

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /app/build/libs/*-all.jar app.jar
COPY --from=build /app/src/main/resources/transfers.json /app/src/main/resources/transfers.json

EXPOSE 8080

# JVM'e maksimum RAM sınırı koyuyoruz (Render'ın 512MB sınırına karşı Java heap'ini 350MB ile sınırlandırıyoruz)
CMD ["java", "-Xmx350m", "-jar", "app.jar"]