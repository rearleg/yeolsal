// Metro config for the yeosal monorepo.
// FE is an npm workspace under yeosal/, so dependencies are hoisted to
// yeosal/node_modules. Metro needs to watch the workspace root and resolve
// modules from both FE/node_modules and yeosal/node_modules. Without this,
// the virtual-metro-entry resolver falls back to expo/AppEntry.js and the
// "Unable to resolve ../../App" build error appears.
//
// Reference: https://docs.expo.dev/guides/monorepos/

const { getDefaultConfig } = require("expo/metro-config");
const path = require("path");

const projectRoot = __dirname;
const workspaceRoot = path.resolve(projectRoot, "..");

const config = getDefaultConfig(projectRoot);

config.watchFolders = [workspaceRoot];

config.resolver.nodeModulesPaths = [
  path.resolve(projectRoot, "node_modules"),
  path.resolve(workspaceRoot, "node_modules")
];

config.resolver.disableHierarchicalLookup = true;

module.exports = config;
