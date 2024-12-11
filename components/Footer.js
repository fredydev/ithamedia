export default function Footer() {
    return (
      <footer className="bg-footer text-white py-8">
        <div className="container mx-auto flex flex-col md:flex-row justify-between items-center">
          <p className="text-sm">
            IthaMedia - Découvrez et apprenez avec des contenus fiables et éducatifs.
          </p>
          <div className="flex gap-4">
            <a href="#">Support</a>
            <a href="#">Confidentialité</a>
            <a href="#">Conditions d'utilisation</a>
            <button className="bg-primary text-white px-4 py-2 rounded-lg">
              Nous contacter
            </button>
          </div>
        </div>
      </footer>
    );
  }
  