# my-proxy

This project uses Quarkus, the Supersonic Subatomic Java Framework.

If you want to learn more about Quarkus, please visit its website: <https://quarkus.io/>.

## Running the application in dev mode

You can run your application in dev mode that enables live coding using:

```shell script
./mvnw compile quarkus:dev
```

> **_NOTE:_**  Quarkus now ships with a Dev UI, which is available in dev mode only at <http://localhost:8080/q/dev/>.

## Packaging and running the application

The application can be packaged using (example):

```shell script
./mvnw clean package -Dquarkus.container-image.build=true -Dquarkus.container-image.push=true -Dquarkus.container-image.group=brunoeas1 -Dquarkus.container-image.name=my-proxy -Dquarkus.container-image.tag=0.0.2-SNAPSHOT
```

Run application using:

```shell script
docker compose -f src/main/docker/docker-compose.yml up --build
```