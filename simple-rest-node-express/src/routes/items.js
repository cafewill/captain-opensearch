'use strict';
const { Router } = require('express');
const db         = require('../db');

const router = Router();

router.get('/', (req, res) => {
  try {
    const items = db.findAll();
    req.log('INFO', `GET /api/items → ${items.length}건`);
    res.json({ status: true, message: '조회 성공', data: items });
  } catch (e) {
    req.log('ERROR', `GET /api/items error: ${e.message}`);
    res.status(500).json({ status: false, message: e.message });
  }
});

router.get('/:id', (req, res) => {
  try {
    const item = db.findById(Number(req.params.id));
    if (!item) return res.status(404).json({ status: false, message: 'Item not found' });
    req.log('INFO', `GET /api/items/${req.params.id}`);
    res.json({ status: true, message: '조회 성공', data: item });
  } catch (e) {
    req.log('ERROR', `GET /api/items/${req.params.id} error: ${e.message}`);
    res.status(500).json({ status: false, message: e.message });
  }
});

router.post('/', (req, res) => {
  try {
    const { name, description, price } = req.body;
    if (!name || price == null) return res.status(400).json({ status: false, message: 'name, price 필수' });
    const item = db.insert({ name, description, price });
    req.log('INFO', `POST /api/items → id=${item.id}`);
    res.status(201).json({ status: true, message: '등록 성공', data: item });
  } catch (e) {
    req.log('ERROR', `POST /api/items error: ${e.message}`);
    res.status(500).json({ status: false, message: e.message });
  }
});

router.put('/:id', (req, res) => {
  try {
    const id = Number(req.params.id);
    if (!db.findById(id)) return res.status(404).json({ status: false, message: 'Item not found' });
    const { name, description, price } = req.body;
    if (!name || price == null) return res.status(400).json({ status: false, message: 'name, price 필수' });
    const item = db.update(id, { name, description, price });
    req.log('INFO', `PUT /api/items/${id}`);
    res.json({ status: true, message: '수정 성공', data: item });
  } catch (e) {
    req.log('ERROR', `PUT /api/items/${req.params.id} error: ${e.message}`);
    res.status(500).json({ status: false, message: e.message });
  }
});

router.delete('/:id', (req, res) => {
  try {
    const id = Number(req.params.id);
    if (!db.remove(id)) return res.status(404).json({ status: false, message: 'Item not found' });
    req.log('INFO', `DELETE /api/items/${id}`);
    res.json({ status: true, message: '삭제 성공', data: null });
  } catch (e) {
    req.log('ERROR', `DELETE /api/items/${req.params.id} error: ${e.message}`);
    res.status(500).json({ status: false, message: e.message });
  }
});

module.exports = router;
