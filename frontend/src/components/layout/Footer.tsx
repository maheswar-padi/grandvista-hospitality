import { Logo } from './Logo';

export function Footer() {
  return (
    <footer className="mt-24 bg-ink-950 text-ink-200">
      <div className="container-page grid gap-10 py-14 md:grid-cols-3">
        <div className="space-y-4">
          <Logo light />
          <p className="max-w-xs text-sm text-ink-300">
            A fictional hotel used to demonstrate a microservices reservation platform. Content, rooms and imagery are illustrative.
          </p>
        </div>
        <div className="text-sm">
          <h2 className="mb-3 font-sans text-xs font-semibold uppercase tracking-[0.18em] text-gold-200">Project</h2>
          <ul className="space-y-2 text-ink-300">
            <li>PS016 · SOA Programming and Microservices (24SDCS03R)</li>
            <li>K L University</li>
          </ul>
        </div>
        <div className="text-sm">
          <h2 className="mb-3 font-sans text-xs font-semibold uppercase tracking-[0.18em] text-gold-200">Important</h2>
          <p className="text-ink-300">
            All payments on this site are processed by a <strong className="text-white">simulated</strong> provider. Never enter real card
            details — the checkout only accepts named test methods.
          </p>
        </div>
      </div>
      <div className="border-t border-ink-800 py-5 text-center text-xs text-ink-400">© GrandVista Hospitality (demo) · Built with Spring Boot, Spring Cloud &amp; React</div>
    </footer>
  );
}
