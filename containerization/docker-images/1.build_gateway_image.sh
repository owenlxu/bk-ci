#!/bin/bash
set -e
echo "######################## BUILD GATEWAY IMAGE START... ########################"
docker build -t mirrors.tencent.com/bkce/bkci-gateway:$1 . -f ./dockerfile/gateway.Dockerfile
docker push mirrors.tencent.com/bkce/bkci-gateway:$1
echo "######################## BUILD GATEWAY IMAGE FINISH ! ########################"
echo ''
