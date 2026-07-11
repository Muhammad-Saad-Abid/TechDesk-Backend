FROM eclipse-temurin:17.0.19_10-jre-noble

RUN groupadd --system --gid 10001 techdesk \
    && useradd --system --uid 10001 --gid techdesk \
        --home-dir /app --shell /usr/sbin/nologin techdesk

WORKDIR /app
COPY --chown=techdesk:techdesk target/*.jar app.jar

EXPOSE 8761

USER 10001:10001

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
