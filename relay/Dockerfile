# Encave relay in a container: docker build -t encave-relay relay && docker run -p 8787:8787 -v encave-relay:/data encave-relay
# Node 22 LTS on Alpine 3.24, pinned by digest (the multi-architecture index; Dependabot updates it).
FROM node:22.23.4-alpine3.24@sha256:fe4f6d74e20370acb04ad889077d14d0f64a9c8ea6a42bc60cddf1e844699914
WORKDIR /app
COPY protocol.mjs server.mjs ./
ENV PORT=8787 DATA_DIR=/data
RUN mkdir -p /data && chown node:node /data
USER node
VOLUME /data
EXPOSE 8787
CMD ["node", "server.mjs"]
