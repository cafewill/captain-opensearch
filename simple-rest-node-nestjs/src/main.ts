import 'dotenv/config';
import 'reflect-metadata';
import { NestFactory }           from '@nestjs/core';
import { AppModule }             from './app.module';
import { OpenSearchWebAppender } from './opensearch.web-appender';

async function bootstrap() {
  const app = await NestFactory.create(AppModule, { logger: ['log', 'error', 'warn'] });
  const port = process.env.PORT ?? 3201;
  await app.listen(port);
  console.log(`simple-rest-node-nestjs running on port ${port}`);

  const appender = app.get(OpenSearchWebAppender);
  process.on('SIGTERM', () => { appender.stop(); process.exit(0); });
  process.on('SIGINT',  () => { appender.stop(); process.exit(0); });
}
bootstrap();
