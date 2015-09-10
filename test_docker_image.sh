#! /bin/bash

DOCKERFILE_PATH=$1
DOCKER_IMAGE=$2
DOCKER_PORT="$(sed -n -e 's/^EXPOSE //p' $DOCKERFILE_PATH/Dockerfile)"

docker build -t $DOCKER_IMAGE $DOCKERFILE_PATH
docker run -e AWS_ACCESS_KEY=$AWS_ACCESS_KEY -e AWS_SECRET_KEY=$AWS_SECRET_KEY -dit -p $DOCKER_PORT:$DOCKER_PORT $DOCKER_IMAGE -Dionroller.modify-environments-whitelist=NONE -Dionroller.modify-environments=false
sleep 15
curl --retry 10 --retry-delay 5 -v http://localhost:$DOCKER_PORT
