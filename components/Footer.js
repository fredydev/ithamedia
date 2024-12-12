export default function Footer() {
    return (
      <footer
        className="bg-gray-800 text-white py-6 px-4 md:fixed md:bottom-0 md:left-0 md:w-full"
      >
        <div className="container mx-auto flex flex-col md:flex-row justify-between gap-8">
          {/* Colonne 1 : Description */}
          <div className="flex-1">
            <h2 className="text-lg font-bold mb-4">IthaMedia</h2>
            <p className="text-sm text-gray-400">
              Découvrez et apprenez avec des contenus fiables et éducatifs.
            </p>
          </div>
  
          {/* Colonne 2 : Liens utiles */}
          <div className="flex-1">
            <h2 className="text-lg font-bold mb-4">Liens utiles</h2>
            <ul className="space-y-2">
              <li>
                <a href="#" className="text-sm text-gray-400 hover:underline">
                  Support utilisateur
                </a>
              </li>
              <li>
                <a href="#" className="text-sm text-gray-400 hover:underline">
                  Politique de confidentialité
                </a>
              </li>
              <li>
                <a href="#" className="text-sm text-gray-400 hover:underline">
                  Conditions d'utilisation
                </a>
              </li>
            </ul>
          </div>
  
          {/* Colonne 3 : Contact */}
          <div className="flex-1">
            <h2 className="text-lg font-bold mb-4">Contactez-nous</h2>
            <button className="bg-orange-500 text-white px-4 py-2 rounded-lg hover:bg-orange-600">
              Nous contacter
            </button>
          </div>
        </div>
      </footer>
    );
  }
  