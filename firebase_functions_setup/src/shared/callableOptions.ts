export const FUNCTIONS_REGION = "europe-west1";

export const trustedCallableOptions = {
  region: FUNCTIONS_REGION,
  enforceAppCheck: false,
  maxInstances: 5,
};

export const hotCallableOptions = {
  ...trustedCallableOptions,
  maxInstances: 10,
};

export const heavyCallableOptions = {
  ...trustedCallableOptions,
  memory: "512MiB" as const,
  maxInstances: 3,
};

export const hotHeavyCallableOptions = {
  ...heavyCallableOptions,
  maxInstances: 3,
};
