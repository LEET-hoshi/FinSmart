FROM node:22-alpine

WORKDIR /workspace

COPY package.json package-lock.json ./
RUN npm ci --omit=dev

COPY scripts/update-magnit-offers.mjs scripts/prepare-offers.mjs ./scripts/
COPY tools/api-anything ./tools/api-anything
COPY fixtures/offers/offers.json ./fixtures/offers/offers.json

CMD ["node", "scripts/prepare-offers.mjs"]
