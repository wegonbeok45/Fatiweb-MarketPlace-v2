module.exports = {
  root: true,
  env: {
    es2022: true,
    node: true,
  },
  parser: "@typescript-eslint/parser",
  parserOptions: {
    ecmaVersion: 2022,
    sourceType: "module",
  },
  plugins: ["@typescript-eslint"],
  extends: [
    "eslint:recommended",
    "plugin:@typescript-eslint/recommended",
  ],
  ignorePatterns: [
    "lib/",
    "lib-scripts/",
    "node_modules/",
  ],
  rules: {
    "@typescript-eslint/no-explicit-any": "off",
    "@typescript-eslint/no-unused-vars": ["warn", {"argsIgnorePattern": "^_"}],
    "max-len": ["warn", {"code": 140, "ignoreStrings": true, "ignoreTemplateLiterals": true}],
  },
};
