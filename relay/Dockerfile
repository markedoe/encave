# Encave relay in a container: docker build -t encave-relay relay && docker run -p 8787:8787 -v encave-relay:/data encave-relay
# Node 22 LTS on Alpine 3.24, pinned by digest (the multi-architecture index; Dependabot updates it).
FROM node:26.12.1-alpine3.24@sha256:40cd011b55415bc894e45b02fdd5a5dfde056921b7d23a64c74f80497af48797
WORKDIR /app
COPY protocol.mjs server.mjs ./
ENV PORT=8787 DATA_DIR=/data
RUN mkdir -p /data && chown node:node /data
USER node
VOLUME /data
EXPOSE 8787
CMD ["node", "server.mjs"]
