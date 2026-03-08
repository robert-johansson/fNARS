// Browser smoke test for fNARS web REPL.
// Usage: npx playwright test test/browser_smoke_test.mjs
// Or directly: node test/browser_smoke_test.mjs (starts its own server)

import { chromium } from 'playwright';
import { createServer } from 'http';
import { readFileSync, existsSync } from 'fs';
import { join, extname } from 'path';

const MIME = { '.html': 'text/html', '.js': 'application/javascript', '.css': 'text/css' };
const PUBLIC = new URL('../public', import.meta.url).pathname;

function serve() {
  return new Promise(resolve => {
    const server = createServer((req, res) => {
      const path = req.url === '/' ? '/index.html' : req.url;
      const file = join(PUBLIC, path);
      if (!existsSync(file)) { res.writeHead(404); res.end(); return; }
      res.writeHead(200, { 'Content-Type': MIME[extname(file)] || 'application/octet-stream' });
      res.end(readFileSync(file));
    });
    server.listen(0, () => resolve(server));
  });
}

async function run() {
  const server = await serve();
  const port = server.address().port;
  const url = `http://localhost:${port}`;
  let passed = 0;
  let failed = 0;

  const browser = await chromium.launch();
  const page = await browser.newPage();

  // Collect console errors
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));

  await page.goto(url);
  await page.waitForSelector('#input');

  // Test 1: Page loads with welcome message
  const welcome = await page.textContent('#output');
  if (welcome.includes('fNARS')) {
    console.log('  PASS: Page loads with welcome message');
    passed++;
  } else {
    console.log('  FAIL: No welcome message');
    failed++;
  }

  // Test 2: Enter Narsese and get response
  await page.fill('#input', '<cat --> animal>. :|:');
  await page.click('#submit');
  await page.waitForTimeout(100);
  const output2 = await page.textContent('#output');
  if (output2.includes('Input:') && output2.includes('cat --> animal')) {
    console.log('  PASS: Narsese input processed');
    passed++;
  } else {
    console.log('  FAIL: Narsese input not processed');
    failed++;
  }

  // Test 3: Run cycles
  await page.fill('#input', '<animal --> being>. :|:');
  await page.click('#submit');
  await page.fill('#input', '5');
  await page.click('#submit');
  await page.waitForTimeout(200);
  const output3 = await page.textContent('#output');
  if (output3.includes('Derived:') || output3.includes('being')) {
    console.log('  PASS: Inference produces derived output');
    passed++;
  } else {
    console.log('  FAIL: No derived output after cycles');
    failed++;
  }

  // Test 4: Question answering (accepts answer or derived belief for cat-->being)
  await page.fill('#input', '<cat --> being>? :|:');
  await page.click('#submit');
  await page.waitForTimeout(300);
  const output4 = await page.textContent('#output');
  if (output4.includes('Answer:') || output4.includes('cat --> being')) {
    console.log('  PASS: Question answering / deduction works');
    passed++;
  } else {
    console.log('  FAIL: No answer or derived belief for <cat --> being>');
    failed++;
  }

  // Test 5: Reset
  await page.click('#ex-reset');
  await page.waitForTimeout(100);
  const output5 = await page.textContent('#output');
  if (output5.includes('Reset.')) {
    console.log('  PASS: Reset works');
    passed++;
  } else {
    console.log('  FAIL: Reset did not work');
    failed++;
  }

  // Test 6: No JS errors
  if (errors.length === 0) {
    console.log('  PASS: No JavaScript errors');
    passed++;
  } else {
    console.log('  FAIL: JavaScript errors:', errors);
    failed++;
  }

  await browser.close();
  server.close();

  console.log(`\n  ${passed}/${passed + failed} browser smoke tests passed`);
  process.exit(failed > 0 ? 1 : 0);
}

console.log('\n=== Browser Smoke Tests ===');
run().catch(e => { console.error(e); process.exit(1); });
