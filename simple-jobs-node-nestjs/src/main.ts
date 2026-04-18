import 'dotenv/config';
import 'reflect-metadata';
import { NestFactory } from '@nestjs/core';
import { AppModule } from './app.module';

async function bootstrap() {
  const app = await NestFactory.create(AppModule, { logger: ['log', 'error', 'warn'] });
  await app.listen(process.env.PORT ?? 3000);
  console.log(`simple-jobs-node-nestjs running on port ${process.env.PORT ?? 3000}`);
}
bootstrap();
