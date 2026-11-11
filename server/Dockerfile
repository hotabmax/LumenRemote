# Lumen Relay — production-образ.
# Сборка:  docker build -t lumen-relay ./server
FROM node:22-alpine

ENV NODE_ENV=production
WORKDIR /app

# Зависимости ставятся отдельным слоем (кэш переиспользуется).
COPY package.json ./
RUN npm install --omit=dev --no-audit --no-fund \
    && npm cache clean --force

COPY relay.js ./
COPY web ./web

USER node
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
  CMD node -e "fetch('http://127.0.0.1:8080/health').then((r)=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"

CMD ["node", "relay.js"]
