# Encave relay in a container: docker build -t encave-relay relay && docker run -p 8787:8787 -v encave-relay:/data encave-relay
FROM node:22.11.0-alpine3.20
WORKDIR /app
COPY protocol.mjs server.mjs ./
ENV PORT=8787 DATA_DIR=/data
RUN mkdir -p /data && chown node:node /data
USER node
VOLUME /data
EXPOSE 8787
CMD ["node", "server.mjs"]
