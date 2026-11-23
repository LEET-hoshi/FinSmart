#!/usr/bin/env node

import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { spawnSync } from "node:child_process";

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
export const projectDirectory = resolve(scriptDirectory, "..");

export function dockerEnvironment(mode = "live", environment = process.env) {
    return {
        ...environment,
        OFFERS_MODE: mode,
    };
}

export function startApplication({
    mode = process.argv.includes("--demo") ? "demo" : "live",
    spawn = spawnSync,
} = {}) {
    console.log(`Запуск FinSmart: каталог ${mode === "demo" ? "демонстрационный" : "Магнита"}`);
    const result = spawn(
        "docker",
        ["compose", "up", "--build", "-d", "--force-recreate", "api"],
        {
            cwd: projectDirectory,
            env: dockerEnvironment(mode),
            stdio: "inherit",
        },
    );
    if (result.error) throw result.error;
    if (result.status !== 0) {
        throw new Error(`docker compose завершился с кодом ${result.status}`);
    }
    console.log("FinSmart запущен.");
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
    try {
        startApplication();
    } catch (error) {
        console.error(`Ошибка запуска FinSmart: ${error.message}`);
        process.exitCode = 1;
    }
}
