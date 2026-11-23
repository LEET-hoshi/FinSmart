import test from "node:test";
import assert from "node:assert/strict";
import {
    dockerEnvironment,
    projectDirectory,
    startApplication,
} from "./start-with-magnit.mjs";

test("рабочий и демонстрационный режим передаются Docker Compose", () => {
    const env = dockerEnvironment("live", { TEST_VALUE: "ok", OFFERS_MODE: "wrong" });
    assert.equal(env.TEST_VALUE, "ok");
    assert.equal(env.OFFERS_MODE, "live");
    assert.equal(dockerEnvironment("demo", {}).OFFERS_MODE, "demo");
});

test("единая команда запускает Docker и пересоздаёт API", () => {
    const calls = [];
    startApplication({
        mode: "live",
        spawn: (command, args, options) => {
            calls.push(["spawn", command, args, options.cwd, options.env.OFFERS_MODE]);
            return { status: 0 };
        },
    });
    assert.deepEqual(calls, [
        [
            "spawn",
            "docker",
            ["compose", "up", "--build", "-d", "--force-recreate", "api"],
            projectDirectory,
            "live",
        ],
    ]);
});
