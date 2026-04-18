'use strict';
const db = require('../db');

async function itemsRoutes(fastify) {
  fastify.get('/', async (request, reply) => {
    try {
      const items = db.findAll();
      request.log.info(`GET /api/items → ${items.length}건`);
      return { status: true, message: '조회 성공', data: items };
    } catch (e) {
      request.log.error(`GET /api/items error: ${e.message}`);
      reply.status(500).send({ status: false, message: e.message });
    }
  });

  fastify.get('/:id', async (request, reply) => {
    try {
      const item = db.findById(Number(request.params.id));
      if (!item) return reply.status(404).send({ status: false, message: 'Item not found' });
      return { status: true, message: '조회 성공', data: item };
    } catch (e) {
      reply.status(500).send({ status: false, message: e.message });
    }
  });

  fastify.post('/', async (request, reply) => {
    try {
      const { name, description, price } = request.body;
      if (!name || price == null) return reply.status(400).send({ status: false, message: 'name, price 필수' });
      const item = db.insert({ name, description, price });
      request.log.info(`POST /api/items → id=${item.id}`);
      reply.status(201).send({ status: true, message: '등록 성공', data: item });
    } catch (e) {
      reply.status(500).send({ status: false, message: e.message });
    }
  });

  fastify.put('/:id', async (request, reply) => {
    try {
      const id = Number(request.params.id);
      if (!db.findById(id)) return reply.status(404).send({ status: false, message: 'Item not found' });
      const { name, description, price } = request.body;
      if (!name || price == null) return reply.status(400).send({ status: false, message: 'name, price 필수' });
      const item = db.update(id, { name, description, price });
      return { status: true, message: '수정 성공', data: item };
    } catch (e) {
      reply.status(500).send({ status: false, message: e.message });
    }
  });

  fastify.delete('/:id', async (request, reply) => {
    try {
      const id = Number(request.params.id);
      if (!db.remove(id)) return reply.status(404).send({ status: false, message: 'Item not found' });
      return { status: true, message: '삭제 성공', data: null };
    } catch (e) {
      reply.status(500).send({ status: false, message: e.message });
    }
  });
}

module.exports = itemsRoutes;
