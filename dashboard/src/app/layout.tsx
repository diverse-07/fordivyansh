import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "Netra Saathi - Guardian Dashboard",
  description: "Netra Saathi guardian interface. Live telemetry and notifications are currently unavailable.",
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="en">
      <body className="antialiased min-h-screen bg-[#090d16] text-gray-100">
        {children}
      </body>
    </html>
  );
}
