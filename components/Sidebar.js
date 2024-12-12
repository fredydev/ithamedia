"use client"
import { useState } from 'react';
import { FaBars, FaTimes } from 'react-icons/fa';

const links = [
  { name: "Accueil", href: "/", icon: "🏠" },
  { name: "Catégories", href: "/categories", icon: "📚" },
  { name: "Recommandations", href: "/recommendations", icon: "⭐" },
  { name: "À propos", href: "/about", icon: "ℹ️" },
];

export default function Sidebar() {
  const [isOpen, setIsOpen] = useState(false);

  return (
    <>
      {/* Sidebar fixe sur desktop */}
      <aside className="hidden md:flex flex-col bg-white shadow-lg w-64 h-screen fixed top-0 left-0 z-40">
        <div className="flex flex-col items-start gap-6 p-6">
          {/* Titre ou Logo */}
          <h1 className="text-primary font-bold text-xl">IthaMedia</h1>

          {/* Liens de navigation */}
          <nav className="flex flex-col gap-4 w-full">
            {links.map((link) => (
              <a
                key={link.name}
                href={link.href}
                className="text-gray-700 hover:text-primary font-medium flex items-center gap-2"
              >
                <span>{link.icon}</span>
                {link.name}
              </a>
            ))}
          </nav>

          {/* Bouton "Proposer un compte/une chaîne" */}
          <button className="bg-primary text-white px-4 py-2 rounded-lg mt-8 w-full">
            Proposer un compte/une chaîne
          </button>
        </div>
      </aside>

      {/* Menu burger sur mobile */}
      <div className="md:hidden">
        {/* Bouton pour ouvrir/fermer le menu */}
        <button
          onClick={() => setIsOpen(!isOpen)}
          className="text-primary p-4 fixed top-4 left-4 z-50"
        >
          {isOpen ? <FaTimes size={24} /> : <FaBars size={24} />}
        </button>

        {/* Menu mobile */}
        {isOpen && (
          <aside className="fixed inset-0 bg-white z-40 p-6 flex flex-col gap-6 shadow-lg">
            {/* Titre ou Logo */}
            <h1 className="text-primary font-bold text-xl">IthaMedia</h1>

            {/* Liens de navigation */}
            <nav className="flex flex-col gap-4">
              {links.map((link) => (
                <a
                  key={link.name}
                  href={link.href}
                  className="text-gray-700 hover:text-primary font-medium flex items-center gap-2"
                  onClick={() => setIsOpen(false)} // Fermer le menu après un clic
                >
                  <span>{link.icon}</span>
                  {link.name}
                </a>
              ))}
            </nav>

            {/* Bouton "Proposer un compte/une chaîne" */}
            <button className="bg-primary text-white px-4 py-2 rounded-lg">
              Proposer un compte/une chaîne
            </button>
          </aside>
        )}
      </div>
    </>
  );
}
