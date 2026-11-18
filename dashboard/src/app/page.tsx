"use client";

import React, { useState } from "react";
import {
  ShieldAlert,
  MapPin,
  BatteryCharging,
  Navigation,
  Eye,
  Flashlight,
  Bell,
  CheckCircle,
  Activity,
  User,
  Radio,
  PhoneCall
} from "lucide-react";

export default function CaretakerDashboard() {
  const [activeSOS, setActiveSOS] = useState(false);
  const [beaconStatus, setBeaconStatus] = useState("Active (Night Strobe)");
  const [userLocation, setUserLocation] = useState({
    lat: "12.9716° N",
    lng: "77.5946° E",
    address: "MG Road Sidewalk, Near Metro Station Gate 2",
    battery: "84%",
    speed: "0.8 m/s (Walking)",
    heading: "North-West (315°)"
  });

  const recentHazards = [
    {
      id: 1,
      zone: "HEAD HEIGHT",
      name: "Low tree branch",
      time: "2 mins ago",
      action: "Spoken warning given: User stepped right",
      type: "head"
    },
    {
      id: 2,
      zone: "TORSO LEVEL",
      name: "Oncoming Bicycle (5m ahead)",
      time: "5 mins ago",
      action: "Safety Strobe pulsed: Cyclist safely diverted",
      type: "vehicle"
    },
    {
      id: 3,
      zone: "GROUND LEVEL",
      name: "Deep Pothole (0.8m drop)",
      time: "11 mins ago",
      action: "Urgent STOP haptic triggered: Collision avoided",
      type: "ground"
    }
  ];

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
            <p className="text-sm text-gray-400">Live Caretaker Telemetry & Emergency Command</p>
          </div>
        </div>

        <div className="flex items-center gap-3">
          <div className="flex items-center gap-2 px-3 py-1.5 rounded-full bg-emerald-950/60 border border-emerald-700/50 text-emerald-400 text-sm">
            <Radio className="w-3.5 h-3.5 animate-pulse text-emerald-400" />
            <span>Pedestrian Live Stream: Online</span>
          </div>

          <button
            onClick={() => setActiveSOS(!activeSOS)}
            className={`px-4 py-2 rounded-xl font-semibold flex items-center gap-2 transition ${
              activeSOS
                ? "bg-red-600 text-white animate-pulse"
                : "bg-gray-800 hover:bg-gray-700 text-gray-300"
            }`}
          >
            <ShieldAlert className="w-4 h-4" />
            {activeSOS ? "SOS BROADCAST ACTIVE" : "Simulate SOS"}
          </button>
        </div>
      </header>

      {/* SOS Alert Banner */}
      {activeSOS && (
        <div className="bg-red-500/10 border-2 border-red-500 rounded-2xl p-4 flex flex-col md:flex-row items-start md:items-center justify-between gap-4 animate-bounce">
          <div className="flex items-center gap-3 text-red-400">
            <ShieldAlert className="w-8 h-8 flex-shrink-0" />
            <div>
              <h3 className="text-lg font-bold">EMERGENCY ALERT: USER TRIGGERED SOS</h3>
              <p className="text-sm text-red-300">
                Triple-tap SOS confirmed at {userLocation.address}. Dispatching automated notification.
              </p>
            </div>
          </div>
          <div className="flex items-center gap-2 w-full md:w-auto">
            <button className="flex-1 md:flex-none px-4 py-2 bg-red-600 hover:bg-red-700 text-white font-bold rounded-xl flex items-center justify-center gap-2">
              <PhoneCall className="w-4 h-4" /> Call User Directly
            </button>
            <button
              onClick={() => setActiveSOS(false)}
              className="px-4 py-2 bg-gray-800 hover:bg-gray-700 text-gray-300 rounded-xl"
            >
              Acknowledge
            </button>
          </div>
        </div>
      )}

      {/* Main Grid */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        {/* Left Column: Live Location & Telemetry */}
        <div className="lg:col-span-2 space-y-6">
          {/* Simulated Map View */}
          <div className="bg-[#111827] border border-gray-800 rounded-2xl p-5 relative overflow-hidden">
            <div className="flex justify-between items-center mb-4">
              <div className="flex items-center gap-2">
                <MapPin className="w-5 h-5 text-blue-400" />
                <h2 className="font-semibold text-lg">Live Footpath Navigation Tracking</h2>
              </div>
              <span className="text-xs bg-blue-500/10 text-blue-400 border border-blue-500/20 px-2.5 py-1 rounded-md">
                MapLibre / GPS Lock ±1.2m
              </span>
            </div>

            {/* Map Canvas Placeholder */}
            <div className="h-72 w-full bg-[#0d131f] rounded-xl border border-gray-800/80 relative flex items-center justify-center overflow-hidden">
              <div className="absolute inset-0 opacity-15 bg-[radial-gradient(#3b82f6_1px,transparent_1px)] [background-size:16px_16px]" />
              
              {/* User Marker */}
              <div className="relative flex flex-col items-center">
                <div className="w-12 h-12 rounded-full bg-blue-500/20 flex items-center justify-center animate-ping absolute" />
                <div className="w-9 h-9 rounded-full bg-blue-600 border-2 border-white flex items-center justify-center shadow-lg shadow-blue-500/50 z-10">
                  <Navigation className="w-5 h-5 text-white transform -rotate-45" />
                </div>
                <div className="mt-2 bg-gray-900/90 backdrop-blur px-3 py-1 rounded-full border border-gray-700 text-xs font-medium text-gray-200 z-10">
                  Rahul (User) • 0.8 m/s
                </div>
              </div>

              {/* Map Footer Info */}
              <div className="absolute bottom-3 left-3 right-3 bg-gray-900/80 backdrop-blur border border-gray-800 p-2.5 rounded-lg flex justify-between text-xs text-gray-400">
                <span>📍 {userLocation.address}</span>
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
                <div className="text-lg font-bold text-gray-100 mt-1">Normal Walk</div>
              </div>

              <div className="bg-gray-900/50 border border-gray-800 p-3 rounded-xl">
                <div className="text-xs text-gray-400 flex items-center gap-1.5">
                  <Flashlight className="w-4 h-4 text-yellow-400" /> Safety Strobe
                </div>
                <div className="text-lg font-bold text-yellow-400 mt-1">Strobe Active</div>
              </div>

              <div className="bg-gray-900/50 border border-gray-800 p-3 rounded-xl">
                <div className="text-xs text-gray-400 flex items-center gap-1.5">
                  <ShieldAlert className="w-4 h-4 text-emerald-400" /> Fall Detector
                </div>
                <div className="text-lg font-bold text-emerald-400 mt-1">Armed & Safe</div>
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
              <span className="text-xs text-gray-400">Live Telemetry</span>
            </div>

            <div className="space-y-3">
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
              <User className="w-5 h-5 text-blue-400" /> Remote Settings
            </h2>
            <div className="space-y-2 text-sm text-gray-300">
              <label className="flex items-center justify-between p-2 rounded-lg bg-gray-900/60 border border-gray-800 cursor-pointer">
                <span>Active Safety Strobe Beacon</span>
                <input type="checkbox" defaultChecked className="w-4 h-4 text-blue-600 rounded" />
              </label>

              <label className="flex items-center justify-between p-2 rounded-lg bg-gray-900/60 border border-gray-800 cursor-pointer">
                <span>Head-Height Hazard Alerts</span>
                <input type="checkbox" defaultChecked className="w-4 h-4 text-blue-600 rounded" />
              </label>

              <label className="flex items-center justify-between p-2 rounded-lg bg-gray-900/60 border border-gray-800 cursor-pointer">
                <span>Harvard Mobilio Spatial Sonar</span>
                <input type="checkbox" defaultChecked className="w-4 h-4 text-blue-600 rounded" />
              </label>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
