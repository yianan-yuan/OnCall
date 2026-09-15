# 飞书适配器：零依赖 Node 脚本，不需要 npm install
FROM node:20-alpine

WORKDIR /app
COPY server.js .

ENV PORT=8080
EXPOSE 8080

CMD ["node", "server.js"]
