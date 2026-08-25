import path from "node:path";

import type { NextConfig } from "next";

import { STATIC_SECURITY_HEADERS } from "./src/lib/security-headers";

const nextConfig: NextConfig = {
  reactStrictMode: true,

  // Without this, Next walks up past the repo and picks whatever lockfile it
  // finds in the home directory as the workspace root, which traces the wrong
  // files into the deployment bundle.
  outputFileTracingRoot: path.join(__dirname),

  /**
   * A floor under every response, including the static assets and images the
   * middleware matcher deliberately skips.
   *
   * The per-request Content-Security-Policy is set in middleware instead,
   * because it carries a nonce that has to be minted per response. This covers
   * what does not vary.
   */
  async headers() {
    return [
      {
        source: "/:path*",
        headers: STATIC_SECURITY_HEADERS.map(({ key, value }) => ({ key, value })),
      },
    ];
  },
};

export default nextConfig;
