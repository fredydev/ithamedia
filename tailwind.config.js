/** @type {import('tailwindcss').Config} */
module.exports = {
  content: ["./app/**/*.{js,jsx}", "./components/**/*.{js,jsx}"],
  theme: {
    extend: {
      colors: {
        primary: "#FF6600",       // Couleur pour le bouton orange
        background: "#F8F9FB",   // Couleur de fond
        footer: "#1D2632",       // Couleur pour le footer
        textDark: "#2D3748",     // Texte sombre
        textGray: "#718096",     // Texte clair
      },
    },
  },
  plugins: [],
};
