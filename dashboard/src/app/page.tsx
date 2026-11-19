"use client";

import React, { useRef, useState } from "react";
import {
  ShieldAlert,
  MapPin,
  BatteryCharging,
  Navigation,
  Eye,
  Flashlight,
  Bell,
  Activity,
  User,
  Radio,
  PhoneCall
} from "lucide-react";

export default function CaretakerDashboard() {
  const [activeSOS, setActiveSOS] = useState(false);
  const previewButton = useRef<HTMLButtonElement>(null);
  const userLocation = {
    lat: "Unknown", lng: "Unknown", address: "No device location received",
    battery: "Unknown", speed: "Unknown", heading: "Unknown"
  };
  const recentHazards: {id: number; zone: string; name: string; time: string; action: string; type: string}[] = [];
  function closePreview() {
    setActiveSOS(false);
    previewButton.current?.focus();
  }

  return (
    <div className="min-h-screen p-4 md:p-8 max-w-7xl mx-auto space-y-6">
      {/* Top Header */}
      <header className="flex flex-col md:flex-row justify-between items-start md:items-center gap-4 border-b border-gray-800 pb-5">
        <div className="flex items-center gap-3">
          <div className="p-2.5 bg-blue-600 rounded-xl shadow-lg shadow-blue-500/20">
            <Eye className="w-7 h-7 text-white" />
          </div>
          <div>
            <h1 className="text-2xl font-bold tracking-tight">Netra Saathi Guardian</h1>
            <p className="text-sm text-gray-400">Guardian dashboard · no device connected</p>
          </div>
        </div>

        <div className="flex items-center gap-3">
          <div className="flex items-center gap-2 px-3 py-1.5 rounded-full bg-gray-900 border border-gray-700 text-gray-300 text-sm">
            <Radio className="w-3.5 h-3.5 text-gray-400" />
            <span role="status">Telemetry unavailable · no device paired</span>
          </div>

          <button
            ref={previewButton}
            aria-expanded={activeSOS}
            aria-controls="alert-preview"
            onClick={() => setActiveSOS(!activeSOS)}
            className={`px-4 py-2 rounded-xl font-semibold flex items-center gap-2 transition ${
              activeSOS
                ? "bg-red-600 text-white animate-pulse motion-reduce:animate-none"
                : "bg-gray-800 hover:bg-gray-700 text-gray-300"
            }`}
          >
            <ShieldAlert className="w-4 h-4" />
            {activeSOS ? "Close alert preview" : "Test local alert preview"}
          </button>
        </div>
      </header>

      {/* SOS Alert Banner */}
      {activeSOS && (
        <div id="alert-preview" role="alert" className="bg-red-500/10 border-2 border-red-500 rounded-2xl p-4 flex flex-col md:flex-row items-start md:items-center justify-between gap-4 animate-bounce motion-reduce:animate-none">
          <div className="flex items-center gap-3 text-red-400">
            <ShieldAlert className="w-8 h-8 flex-shrink-0" />
            <div>
              <h3 className="text-lg font-bold">LOCAL ALERT PREVIEW</h3>
              <p className="text-sm text-red-300">
                This is a local preview. No user SOS was received and no notification is sent.
              </p>
            </div>
          </div>
          <div className="flex items-center gap-2 w-full md:w-auto">
            <button disabled aria-describedby="connection-limits" className="disabled:opacity-50 flex-1 md:flex-none px-4 py-2 bg-red-600 hover:bg-red-700 text-white font-bold rounded-xl flex items-center justify-center gap-2">
              <PhoneCall className="w-4 h-4" /> Calling unavailable
            </button>
            <button
              onClick={closePreview}
              className="px-4 py-2 bg-gray-800 hover:bg-gray-700 text-gray-300 rounded-xl"
            >
              Close preview
            </button>
          </div>
        </div>
      )}

      <p id="connection-limits" className="text-sm text-gray-300" role="note">
        Live location, guardian notifications, fall detection and remote commands are not connected.
        Controls remain unavailable until these services exist. This dashboard cannot monitor an emergency.
      </p>
      {/* Main Grid */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        {/* Left Column: Live Location & Telemetry */}
        <div className="lg:col-span-2 space-y-6">
          {/* Simulated Map View */}
          <div className="bg-[#111827] border border-gray-800 rounded-2xl p-5 relative overflow-hidden">
            <div className="flex justify-between items-center mb-4">
              <div className="flex items-center gap-2">
                <MapPin className="w-5 h-5 text-blue-400" />
                <h2 className="font-semibold text-lg">Device location</h2>
              </div>
              <span className="text-xs bg-blue-500/10 text-blue-400 border border-blue-500/20 px-2.5 py-1 rounded-md">
                No GPS fix received
              </span>
            </div>

            {/* Map Canvas Placeholder */}
            <div className="h-72 w-full bg-[#0d131f] rounded-xl border border-gray-800/80 relative flex items-center justify-center overflow-hidden">
              
              <p className="p-6 text-center text-gray-300">Location unavailable. No device telemetry is connected.</p>

              {/* Map Footer Info */}
              <div className="absolute bottom-3 left-3 right-3 bg-gray-900/80 backdrop-blur border border-gray-800 p-2.5 rounded-lg flex justify-between text-xs text-gray-400">
                <span>{userLocation.address}</span>
                <span>Lat: {userLocation.lat} | Lng: {userLocation.lng}</span>
              </div>
            </div>

            {/* Quick Metrics Bar */}
            <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 mt-4">
              <div className="bg-gray-900/50 border border-gray-800 p-3 rounded-xl">
                <div className="text-xs text-gray-400 flex items-center gap-1.5">
                  <BatteryCharging className="w-4 h-4 text-emerald-400" /> Phone Battery
                </div>
                <div className="text-lg font-bold text-gray-100 mt-1">{userLocation.battery}</div>
              </div>

              <div className="bg-gray-900/50 border border-gray-800 p-3 rounded-xl">
                <div className="text-xs text-gray-400 flex items-center gap-1.5">
                  <Activity className="w-4 h-4 text-blue-400" /> Speed & Gait
                </div>
                <div className="text-lg font-bold text-gray-100 mt-1">Unknown</div>
              </div>

              <div className="bg-gray-900/50 border border-gray-800 p-3 rounded-xl">
                <div className="text-xs text-gray-400 flex items-center gap-1.5">
                  <Flashlight className="w-4 h-4 text-yellow-400" /> Safety Strobe
                </div>
                <div className="text-lg font-bold text-yellow-400 mt-1">Unknown</div>
              </div>

              <div className="bg-gray-900/50 border border-gray-800 p-3 rounded-xl">
                <div className="text-xs text-gray-400 flex items-center gap-1.5">
                  <ShieldAlert className="w-4 h-4 text-emerald-400" /> Fall Detector
                </div>
                <div className="text-lg font-bold text-gray-300 mt-1">Unavailable</div>
              </div>
            </div>
          </div>
        </div>

        {/* Right Column: 3-Zone Hazard Feed & Controls */}
        <div className="space-y-6">
          {/* Real-time 3-Zone Hazard Log */}
          <div className="bg-[#111827] border border-gray-800 rounded-2xl p-5">
            <div className="flex items-center justify-between mb-4">
              <h2 className="font-semibold text-lg flex items-center gap-2">
                <Bell className="w-5 h-5 text-yellow-400" /> 3-Zone Obstacle Log
              </h2>
              <span className="text-xs text-gray-400">No telemetry received</span>
            </div>

            <div className="space-y-3">
              {recentHazards.length === 0 && <p className="text-sm text-gray-300">No hazard data received. This does not mean the path is safe.</p>}
              {recentHazards.map((item) => (
                <div
                  key={item.id}
                  className="p-3.5 bg-gray-900/70 border border-gray-800 rounded-xl space-y-1.5"
                >
                  <div className="flex justify-between items-center text-xs">
                    <span
                      className={`font-bold px-2 py-0.5 rounded ${
                        item.zone === "HEAD HEIGHT"
                          ? "bg-purple-500/20 text-purple-400 border border-purple-500/30"
                          : item.zone === "TORSO LEVEL"
                          ? "bg-blue-500/20 text-blue-400 border border-blue-500/30"
                          : "bg-red-500/20 text-red-400 border border-red-500/30"
                      }`}
                    >
                      {item.zone}
                    </span>
                    <span className="text-gray-500">{item.time}</span>
                  </div>
                  <div className="font-semibold text-gray-200 text-sm">{item.name}</div>
                  <p className="text-xs text-gray-400">{item.action}</p>
                </div>
              ))}
            </div>
          </div>

          {/* Remote Guardian Assistance */}
          <div className="bg-[#111827] border border-gray-800 rounded-2xl p-5 space-y-4">
            <h2 className="font-semibold text-lg flex items-center gap-2">
              <User className="w-5 h-5 text-blue-400" /> Remote settings · unavailable
            </h2>
            <div className="space-y-2 text-sm text-gray-300">
              <label className="flex items-center justify-between p-2 rounded-lg bg-gray-900/60 border border-gray-800 cursor-pointer">
                <span>Active Safety Strobe Beacon</span>
                <input type="checkbox" disabled aria-describedby="connection-limits" className="w-4 h-4 text-blue-600 rounded" />
              </label>

              <label className="flex items-center justify-between p-2 rounded-lg bg-gray-900/60 border border-gray-800 cursor-pointer">
                <span>Head-Height Hazard Alerts</span>
                <input type="checkbox" disabled aria-describedby="connection-limits" className="w-4 h-4 text-blue-600 rounded" />
              </label>

              <label className="flex items-center justify-between p-2 rounded-lg bg-gray-900/60 border border-gray-800 cursor-pointer">
                <span>Harvard Mobilio Spatial Sonar</span>
                <input type="checkbox" disabled aria-describedby="connection-limits" className="w-4 h-4 text-blue-600 rounded" />
              </label>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
