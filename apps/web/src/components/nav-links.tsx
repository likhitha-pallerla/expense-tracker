"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";

const links = [
  { href: "/dashboard", label: "Dashboard" },
  { href: "/transactions", label: "Transactions" },
  { href: "/accounts", label: "Accounts" },
  { href: "/review", label: "Review" },
];

export function NavLinks({ reviewCount = 0 }: { reviewCount?: number }) {
  const pathname = usePathname();

  return (
    <nav className="flex gap-1" aria-label="Main">
      {links.map((link) => {
        const active =
          pathname === link.href || pathname.startsWith(`${link.href}/`);
        const badge = link.href === "/review" ? reviewCount : 0;

        return (
          <Link
            key={link.href}
            href={link.href}
            aria-current={active ? "page" : undefined}
            className={`flex items-center gap-1.5 rounded-md px-3 py-1.5 text-sm font-medium transition ${
              active
                ? "bg-neutral-900 text-white dark:bg-neutral-100 dark:text-neutral-900"
                : "text-neutral-600 hover:bg-neutral-100 dark:text-neutral-400 dark:hover:bg-neutral-900"
            }`}
          >
            {link.label}
            {badge > 0 && (
              <span
                aria-label={`${badge} awaiting review`}
                className="rounded-full bg-amber-500 px-1.5 text-xs font-semibold text-white"
              >
                {badge}
              </span>
            )}
          </Link>
        );
      })}
    </nav>
  );
}
