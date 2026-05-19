export const FUNCTIONS_REGION = "europe-west1";

export const trustedCallableOptions = {
  region: FUNCTIONS_REGION,
  enforceAppCheck: false,
};

export const hotCallableOptions = {
  ...trustedCallableOptions,
  minInstances: 1,
};

export const heavyCallableOptions = {
  ...trustedCallableOptions,
  memory: "512MiB" as const,
};

export const hotHeavyCallableOptions = {
  ...heavyCallableOptions,
  minInstances: 1,
};
