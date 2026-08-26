FROM eclipse-temurin:17-jdk-alpine AS build
WORKDIR /app
COPY . .
RUN chmod +x gradlew
RUN ./gradlew shadowJar --no-daemon

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Alpine Linux üzerinde SQLite sürücüsünün (C native kütüphanesi) sorunsuz çalışması için
RUN apk add --no-cache gcompat

COPY --from=build /app/build/libs/*-all.jar app.jar
COPY --from=build /app/src/main/resources/basketball.db /app/basketball.db
# 💡 football.db artık BURADA kopyalanmıyor — Git LFS bant genişliğini tüketmesin
# diye, uygulama ayağa kalkarken GitHub Releases'tan otomatik indiriliyor
# (bkz. Application.kt içindeki ensureFootballDbExists() fonksiyonu).

EXPOSE 8080

CMD ["java", "-Xmx300m", "-jar", "app.jar"]
