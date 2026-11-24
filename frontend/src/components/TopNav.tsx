"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";

const NAV_ITEMS = [
  { href: "/", label: "Playground" },
  { href: "/benchmarks", label: "Benchmarks" },
] as const;

export function TopNav() {
  const pathname = usePathname();

  return (
    <header className="bg-[var(--surface-panel)] border-b border-[var(--border-hairline)] sticky top-0 z-20">
      <div className="max-w-[1440px] mx-auto px-4 sm:px-6 lg:px-8 h-14 flex items-center justify-between gap-6">
        <div className="flex items-center gap-8">
          <Link
            href="/"
            className="font-display font-bold text-lg tracking-tight text-[var(--ink-primary)] flex items-center gap-2.5 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--signal-cobalt)]"
          >
            <span
              aria-hidden="true"
              className="inline-block w-2.5 h-2.5 bg-[var(--signal-cobalt)]"
            />
            AutoHeal
          </Link>

          <nav aria-label="Primary" className="flex items-center gap-1">
            {NAV_ITEMS.map((item) => {
              const active =
                item.href === "/"
                  ? pathname === "/"
                  : pathname.startsWith(item.href);
              return (
                <Link
                  key={item.href}
                  href={item.href}
                  aria-current={active ? "page" : undefined}
                  className={`px-3 py-1.5 text-sm font-medium rounded-sm transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--signal-cobalt)] ${
                    active
                      ? "bg-[var(--surface-muted)] text-[var(--ink-primary)]"
                      : "text-[var(--ink-secondary)] hover:text-[var(--ink-primary)]"
                  }`}
                >
                  {item.label}
                </Link>
              );
            })}
          </nav>
        </div>

        <div className="hidden sm:flex items-center gap-3 text-xs text-[var(--ink-secondary)] font-mono">
          <span className="px-2 py-0.5 border border-[var(--border-hairline)] bg-[var(--surface-canvas)]">
            Java 21 sandbox
          </span>
          <span className="px-2 py-0.5 border border-[var(--border-hairline)] bg-[var(--surface-canvas)]">
            --network=none
          </span>
        </div>
      </div>
    </header>
  );
}
