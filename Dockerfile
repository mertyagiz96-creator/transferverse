FROM eclipse-temurin:17-jdk-alpine AS build
WORKDIR /app
COPY . .
RUN chmod +x gradlew
RUN ./gradlew shadowJar --no-daemon

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
# Derlenen JAR dosyasını kopyala
COPY --from=build /app/build/libs/*-all.jar app.jar

# 178 MB'lık veri dosyasını (football.db veya transfers.json hangisiyse) doğrudan kopyalıyoruz:
COPY --from=build /app/src/main/resources/football.db /app/src/main/resources/football.db
# Eğer transfers.json kullanıyorsan üstteki satırı şu yapabilirsin:
# COPY --from=build /app/src/main/resources/transfers.json /app/src/main/resources/transfers.json

EXPOSE 8080
CMD ["java", "-jar", "app.jar"]