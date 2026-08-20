import path from "node:path";

import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  reactStrictMode: true,

  // Without this, Next walks up past the repo and picks whatever lockfile it
  // finds in the home directory as the workspace root, which traces the wrong
  // files into the deployment bundle.
  outputFileTracingRoot: path.join(__dirname),
};

export default nextConfig;
