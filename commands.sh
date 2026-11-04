# Команды для сборки нативного образа и запуска
docker build --no-cache -f src/main/docker/Dockerfile.multistage -t site-project/native-image .
docker run -i --rm -p 8080:8080 --env-file .env site-project/native-image