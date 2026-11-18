import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "Netra Saathi — Caretaker Live Dashboard",
  description: "Real-time pedestrian tracking, 3-zone hazard feed, and SOS alert monitoring for Netra Saathi guardians.",
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
