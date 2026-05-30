FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /app

COPY pom.xml .
COPY src ./src

RUN mvn -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app

RUN mkdir -p /app/data

COPY --from=build /app/target/tattoo-bot.jar /app/bot.jar

ENV DB_PATH=/app/data/bot.db

CMD ["java", "-jar", "/app/bot.jar"]
