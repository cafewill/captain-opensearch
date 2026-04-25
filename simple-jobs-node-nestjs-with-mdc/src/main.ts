import 'dotenv/config';
import 'reflect-metadata';
import { NestFactory } from '@nestjs/core';
import { AppModule } from './app.module';

async function bootstrap() {
  const app = await NestFactory.create(AppModule, { logger: ['log', 'error', 'warn'] });
  const port = process.env.PORT ?? 3011;
  await app.listen(port);
  console.log(`simple-jobs-node-nestjs-with-mdc running on port ${port}`);
}
bootstrap();
