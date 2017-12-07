```sh
mvn clean package
mvn compile
mvn package
mvn exec:java
```


```sh
mvn clean package && \
env \
UPLOAD_DIRECTORY=./in/ \
DOWNLOAD_DIRECTORY=./out/ \
TENANT_ID=9bdef4bf-da9d-4e5d-a25a-xxxx \
CLIENT_ID=ef0a1623-0377-412e-84da-xxxx \
CLIENT_KEY=a2ojjEQDTkJahM/xxxx+6VMxM5m78Jc= \
REST_API_ENDPOINT=https://xxxx.restv2.japaneast.media.azure.net/api/ \
QUEUE_ACCOUNT_NAME=xxxx \
QUEUE_ACCOUNT_KEY=xxxx/8m5pFBn8rSabPYj2jCQViIFymEb0O9pHo9lKrFoWJRiwbOykn6sM9KopTCXDl/v6ZhTjgNuIUnJA== \
QUEUE_URI=https://xxxx.queue.core.windows.net/ \
mvn exec:java
```


# To change this license header, choose License Headers in Project Properties.
# To change this template file, choose Tools | Templates
# and open the template in the editor.

TENANT_ID=9bdef4bf-da9d-4e5d-a25a-x
CLIENT_ID=ef0a1623-0377-412e-84da-x
CLIENT_KEY=a2ojjEQDTkJahM/x+6VMxM5m78Jc=
REST_API_ENDPOINT=https://x.restv2.japaneast.media.azure.net/api/

# QUEUE_ACCOUNT = Storage Account のアクセスキー

QUEUE_ACCOUNT_NAME=x
QUEUE_ACCOUNT_KEY=x/8m5pFBn8rSabPYj2jCQViIFymEb0O9pHo9lKrFoWJRiwbOykn6sM9KopTCXDl/v6ZhTjgNuIUnJA==
QUEUE_URI=https://x.queue.core.windows.net/




