#!/usr/bin/env node
/**
 * scoova-upload-mapping — upload Android ProGuard / R8 mapping files to
 * Scoova Monitor so deobfuscated stack frames show in the dashboard.
 *
 * After a release build, R8 produces a mapping.txt at:
 *   app/build/outputs/mapping/<flavor>Release/mapping.txt
 *
 * Usage:
 *   scoova-upload-mapping \
 *     --api-key sm_xxx \
 *     --version 1.4.0 \
 *     --build 42 \
 *     --file app/build/outputs/mapping/release/mapping.txt
 *
 * Or as a Gradle task — drop into app/build.gradle.kts:
 *   tasks.register<Exec>("uploadScoovaMapping") {
 *       dependsOn("assembleRelease")
 *       commandLine = listOf(
 *           "npx", "scoova-upload-mapping",
 *           "--api-key", System.getenv("SCOOVA_API_KEY") ?: "",
 *           "--version", android.defaultConfig.versionName ?: "0.0.0",
 *           "--build",   android.defaultConfig.versionCode?.toString() ?: "0",
 *           "--file",    "${'$'}buildDir/outputs/mapping/release/mapping.txt",
 *       )
 *   }
 *
 * No external dependencies — uses Node stdlib.
 */

'use strict';

const fs = require('fs');
const path = require('path');
const https = require('https');
const http = require('http');
const url = require('url');

const args = parseArgs(process.argv.slice(2));

if (!args['api-key'] || !args.version || (!args.file && !args.dir)) {
    usage();
    process.exit(1);
}

const endpoint = args.endpoint || 'https://monitor.scoo-va.info';
const buildNumber = String(args.build || '');

main().catch(err => {
    console.error('FAILED:', err.message || err);
    process.exit(2);
});

async function main() {
    // Either upload one explicit --file, or scan --dir for mapping.txt
    const files = [];
    if (args.file) {
        const f = path.resolve(args.file);
        if (!fs.existsSync(f)) throw new Error(`mapping file not found: ${f}`);
        files.push(f);
    } else {
        const dir = path.resolve(args.dir);
        if (!fs.existsSync(dir)) throw new Error(`directory not found: ${dir}`);
        files.push(...walk(dir).filter(p => path.basename(p).toLowerCase() === 'mapping.txt'));
        if (files.length === 0) {
            console.error(`no mapping.txt found under ${dir}`);
            return;
        }
    }
    console.log(`Uploading ${files.length} mapping file(s) to ${endpoint} for v${args.version} build ${buildNumber || '(none)'}`);

    let ok = 0, failed = 0;
    for (const f of files) {
        const sizeMB = (fs.statSync(f).size / 1024 / 1024).toFixed(2);
        try {
            await uploadOne(f);
            console.log(`  ✓ ${path.relative(process.cwd(), f)} (${sizeMB} MB)`);
            ok++;
        } catch (e) {
            console.error(`  ✗ ${path.relative(process.cwd(), f)}: ${e.message}`);
            failed++;
        }
    }
    console.log(`\nDone: ${ok} uploaded, ${failed} failed.`);
    if (failed > 0) process.exit(3);
}

function uploadOne(filePath) {
    return new Promise((resolve, reject) => {
        const data = fs.readFileSync(filePath);
        const name = path.basename(filePath);
        const target = url.parse(endpoint + '/v1/upload/mapping');
        const isHttps = target.protocol === 'https:';
        const lib = isHttps ? https : http;

        const boundary = '----scoova' + Math.random().toString(36).slice(2);
        const parts = [];
        const field = (k, v) => parts.push(Buffer.from(
            `--${boundary}\r\nContent-Disposition: form-data; name="${k}"\r\n\r\n${v}\r\n`,
        ));
        field('appVersion', args.version);
        field('buildNumber', buildNumber);
        field('platform', 'android');
        field('mappingType', 'proguard');
        parts.push(Buffer.from(
            `--${boundary}\r\nContent-Disposition: form-data; name="mapping"; filename="${name}"\r\n` +
            `Content-Type: text/plain\r\n\r\n`,
        ));
        parts.push(data);
        parts.push(Buffer.from(`\r\n--${boundary}--\r\n`));
        const body = Buffer.concat(parts);

        const req = lib.request({
            hostname: target.hostname,
            port: target.port,
            path: target.path,
            method: 'POST',
            headers: {
                'X-API-Key': args['api-key'],
                'Content-Type': `multipart/form-data; boundary=${boundary}`,
                'Content-Length': body.length,
            },
            timeout: 120_000,
        }, res => {
            const chunks = [];
            res.on('data', c => chunks.push(c));
            res.on('end', () => {
                const status = res.statusCode || 0;
                const text = Buffer.concat(chunks).toString('utf8');
                if (status >= 200 && status < 300) return resolve();
                reject(new Error(`HTTP ${status}: ${text.slice(0, 200)}`));
            });
        });
        req.on('error', reject);
        req.on('timeout', () => req.destroy(new Error('upload timed out')));
        req.write(body);
        req.end();
    });
}

function walk(dir) {
    const out = [];
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
        const p = path.join(dir, entry.name);
        if (entry.isDirectory()) out.push(...walk(p));
        else out.push(p);
    }
    return out;
}

function parseArgs(argv) {
    const out = {};
    for (let i = 0; i < argv.length; i++) {
        const a = argv[i];
        if (a.startsWith('--')) {
            const k = a.slice(2);
            const v = (argv[i + 1] && !argv[i + 1].startsWith('--')) ? argv[++i] : 'true';
            out[k] = v;
        }
    }
    return out;
}

function usage() {
    console.error(`scoova-upload-mapping — upload Android ProGuard/R8 mapping.txt to Scoova Monitor

Usage:
  scoova-upload-mapping --api-key <KEY> --version <V> [--build <BUILD>] (--file <PATH> | --dir <DIR>) [--endpoint <URL>]

Required:
  --api-key   Scoova Monitor API key for the Android platform
  --version   App version, e.g. "1.4.0"
  --file      Path to mapping.txt
  --dir       OR a directory to scan recursively for mapping.txt (typical: ./app/build/outputs/mapping)

Optional:
  --build     Build number (e.g. versionCode)
  --endpoint  Override the Scoova endpoint (default: https://monitor.scoo-va.info)

Examples:
  # Single file
  scoova-upload-mapping --api-key \$SCOOVA_API_KEY --version 1.4.0 --build 42 \\
    --file app/build/outputs/mapping/release/mapping.txt

  # Scan a directory (handles flavors)
  scoova-upload-mapping --api-key \$SCOOVA_API_KEY --version 1.4.0 \\
    --dir app/build/outputs/mapping
`);
}
